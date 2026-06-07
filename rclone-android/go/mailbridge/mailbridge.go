// Package mailbridge is a minimal Go bridge to Proton Mail (via
// rclone's fork of go-proton-api) for use through gomobile on Android.
//
// It mirrors the rcbridge pattern: a single JSON-in/JSON-out entry point
// [MbRPC] returning [MbResult], so the gomobile surface stays tiny
// (string/int/error only). All Proton state lives in an in-process session
// registry keyed by an opaque sessionId chosen by the Kotlin caller.
//
// Transport routing: when a "socks" address (e.g. "127.0.0.1:1080") is
// supplied at login, outgoing HTTPS is dialled through that SOCKS5 proxy
// using net/http's built-in socks5:// support — this is how Haven pipes
// Proton traffic through a per-profile WireGuard/Tailscale tunnel
// (TunnelResolver.socksEndpoint), the same mechanism rclone uses.
//
// Message crypto: messages are fetched and decrypted to standard RFC822
// MIME via proton.BuildRFC822, so no local IMAP server (gluon) is needed.
package mailbridge

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"sync"

	"github.com/ProtonMail/gopenpgp/v2/crypto"
	proton "github.com/rclone/go-proton-api"
)

// MbResult holds the output of a bridge call. Status mirrors HTTP-ish
// semantics: 200 OK, 4xx/5xx error (with Output = {"error": "..."}).
type MbResult struct {
	Status int64
	Output string
}

// session holds one logged-in, unlocked Proton account.
type session struct {
	manager *proton.Manager
	client  *proton.Client
	userKR  *crypto.KeyRing
	addrKRs map[string]*crypto.KeyRing
}

var (
	sessionsMu sync.Mutex
	sessions   = map[string]*session{}
)

// MbRPC dispatches a JSON request to a named method. See each handler for
// its expected input shape. Returns a *MbResult (never nil).
func MbRPC(method, input string) *MbResult {
	var req map[string]any
	if input == "" {
		input = "{}"
	}
	if err := json.Unmarshal([]byte(input), &req); err != nil {
		return errf(400, "invalid JSON input: %v", err)
	}
	ctx := context.Background()

	switch method {
	case "login":
		return doLogin(ctx, req)
	case "listFolders":
		return doListFolders(ctx, req)
	case "listMessages":
		return doListMessages(ctx, req)
	case "getMessage":
		return doGetMessage(ctx, req)
	case "send":
		// Compose/send is implemented in a later stage (needs per-recipient
		// encryption + CreateDraft/SendDraft). Fail loudly rather than
		// pretend success.
		return errf(501, "send not yet implemented")
	case "logout":
		return doLogout(ctx, req)
	default:
		return errf(404, "unknown method %q", method)
	}
}

// doLogin performs SRP login, optional TOTP 2FA, and keyring unlock, then
// stores the session. Input:
//
//	{ "sessionId":"..", "username":"..", "password":"..",
//	  "mailboxPassword":"..",   // only for two-password-mode accounts
//	  "twoFA":"123456",         // required iff the account has TOTP enabled
//	  "appVersion":"..", "userAgent":"..", "hostUrl":"..", "socks":"host:port" }
//
// Output on success: { "uid", "accessToken", "refreshToken", "saltedKeyPass" }
// (base64 saltedKeyPass), which the caller persists for resume.
func doLogin(ctx context.Context, req map[string]any) *MbResult {
	sessionID := str(req, "sessionId")
	if sessionID == "" {
		return errf(400, "sessionId required")
	}
	username := str(req, "username")
	password := str(req, "password")
	if username == "" || password == "" {
		return errf(400, "username and password required")
	}

	m := newManager(req)

	c, auth, err := m.NewClientWithLogin(ctx, username, []byte(password))
	if err != nil {
		return errf(401, "login failed: %v", err)
	}

	if auth.TwoFA.Enabled&proton.HasTOTP != 0 {
		code := str(req, "twoFA")
		if code == "" {
			return errf(412, "2fa_required")
		}
		if err := c.Auth2FA(ctx, proton.Auth2FAReq{TwoFactorCode: code}); err != nil {
			return errf(401, "2fa failed: %v", err)
		}
	}

	keyPass := []byte(password)
	if auth.PasswordMode == proton.TwoPasswordMode {
		mbp := str(req, "mailboxPassword")
		if mbp == "" {
			return errf(412, "mailbox_password_required")
		}
		keyPass = []byte(mbp)
	}

	userKR, addrKRs, saltedKeyPass, err := unlockKeyrings(ctx, c, keyPass, nil)
	if err != nil {
		return errf(500, "unlock failed: %v", err)
	}

	store(sessionID, &session{manager: m, client: c, userKR: userKR, addrKRs: addrKRs})

	out, _ := json.Marshal(map[string]string{
		"uid":           auth.UID,
		"accessToken":   auth.AccessToken,
		"refreshToken":  auth.RefreshToken,
		"saltedKeyPass": base64.StdEncoding.EncodeToString(saltedKeyPass),
	})
	return &MbResult{Status: 200, Output: string(out)}
}

// doListFolders returns Proton labels of folder/system/label kinds as a JSON
// array. Input: { "sessionId":".." }.
func doListFolders(ctx context.Context, req map[string]any) *MbResult {
	s, e := sess(req)
	if e != nil {
		return e
	}
	labels, err := s.client.GetLabels(ctx, proton.LabelTypeSystem, proton.LabelTypeFolder, proton.LabelTypeLabel)
	if err != nil {
		return errf(502, "GetLabels failed: %v", err)
	}
	out, _ := json.Marshal(labels)
	return &MbResult{Status: 200, Output: string(out)}
}

// doListMessages returns message metadata for a label as a JSON array.
// Input: { "sessionId":"..", "labelID":"..", "desc": true }.
func doListMessages(ctx context.Context, req map[string]any) *MbResult {
	s, e := sess(req)
	if e != nil {
		return e
	}
	filter := proton.MessageFilter{LabelID: str(req, "labelID")}
	if b, ok := req["desc"].(bool); ok {
		filter.Desc = proton.Bool(b)
	}
	meta, err := s.client.GetMessageMetadata(ctx, filter)
	if err != nil {
		return errf(502, "GetMessageMetadata failed: %v", err)
	}
	out, _ := json.Marshal(meta)
	return &MbResult{Status: 200, Output: string(out)}
}

// doGetMessage fetches a message, decrypts it (plus its attachments), and
// returns it as a base64-encoded RFC822 MIME blob.
// Input: { "sessionId":"..", "messageID":".." }.
// Output: { "rfc822": "<base64>" }.
func doGetMessage(ctx context.Context, req map[string]any) *MbResult {
	s, e := sess(req)
	if e != nil {
		return e
	}
	id := str(req, "messageID")
	if id == "" {
		return errf(400, "messageID required")
	}
	msg, err := s.client.GetMessage(ctx, id)
	if err != nil {
		return errf(502, "GetMessage failed: %v", err)
	}

	kr := s.addrKRs[msg.AddressID]
	if kr == nil {
		kr = s.userKR
	}

	attData := map[string][]byte{}
	for _, att := range msg.Attachments {
		data, err := s.client.GetAttachment(ctx, att.ID)
		if err != nil {
			return errf(502, "GetAttachment %s failed: %v", att.ID, err)
		}
		attData[att.ID] = data
	}

	raw, err := proton.BuildRFC822(kr, msg, attData)
	if err != nil {
		return errf(500, "BuildRFC822 failed: %v", err)
	}
	out, _ := json.Marshal(map[string]string{
		"rfc822": base64.StdEncoding.EncodeToString(raw),
	})
	return &MbResult{Status: 200, Output: string(out)}
}

// doLogout revokes the Proton session and drops it from the registry.
func doLogout(ctx context.Context, req map[string]any) *MbResult {
	sessionID := str(req, "sessionId")
	sessionsMu.Lock()
	s := sessions[sessionID]
	delete(sessions, sessionID)
	sessionsMu.Unlock()
	if s != nil && s.client != nil {
		_ = s.client.AuthDelete(ctx)
	}
	return &MbResult{Status: 200, Output: "{}"}
}

// unlockKeyrings derives the salted key passphrase (when keyPass is given)
// or uses a previously-stored saltedKeyPass, then unlocks the user and
// address keyrings. Mirrors Proton-API-Bridge/common.getAccountKRs.
func unlockKeyrings(ctx context.Context, c *proton.Client, keyPass, saltedKeyPass []byte) (*crypto.KeyRing, map[string]*crypto.KeyRing, []byte, error) {
	user, err := c.GetUser(ctx)
	if err != nil {
		return nil, nil, nil, err
	}
	addrs, err := c.GetAddresses(ctx)
	if err != nil {
		return nil, nil, nil, err
	}
	if saltedKeyPass == nil {
		salts, err := c.GetSalts(ctx)
		if err != nil {
			return nil, nil, nil, err
		}
		saltedKeyPass, err = salts.SaltForKey(keyPass, user.Keys.Primary().ID)
		if err != nil {
			return nil, nil, nil, err
		}
	}
	userKR, addrKRs, err := proton.Unlock(user, addrs, saltedKeyPass, nil)
	if err != nil {
		return nil, nil, nil, err
	}
	return userKR, addrKRs, saltedKeyPass, nil
}

// newManager builds a Proton Manager, routing through a SOCKS5 proxy when a
// "socks" host:port is supplied (net/http understands socks5:// URLs natively).
func newManager(req map[string]any) *proton.Manager {
	opts := []proton.Option{}
	if v := str(req, "appVersion"); v != "" {
		opts = append(opts, proton.WithAppVersion(v))
	}
	if v := str(req, "hostUrl"); v != "" {
		opts = append(opts, proton.WithHostURL(v))
	}
	if socks := str(req, "socks"); socks != "" {
		proxyURL := &url.URL{Scheme: "socks5", Host: socks}
		transport := http.DefaultTransport.(*http.Transport).Clone()
		transport.Proxy = http.ProxyURL(proxyURL)
		opts = append(opts, proton.WithTransport(transport))
	}
	return proton.New(opts...)
}

// ---- helpers ----

func store(id string, s *session) {
	sessionsMu.Lock()
	sessions[id] = s
	sessionsMu.Unlock()
}

func sess(req map[string]any) (*session, *MbResult) {
	id := str(req, "sessionId")
	sessionsMu.Lock()
	s := sessions[id]
	sessionsMu.Unlock()
	if s == nil {
		return nil, errf(440, "no session %q (login first)", id)
	}
	return s, nil
}

func str(m map[string]any, k string) string {
	if v, ok := m[k].(string); ok {
		return v
	}
	return ""
}

func errf(status int64, format string, args ...any) *MbResult {
	out, _ := json.Marshal(map[string]string{"error": fmt.Sprintf(format, args...)})
	return &MbResult{Status: status, Output: string(out)}
}
