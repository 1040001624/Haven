package mailbridge

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
)

// TestLiveProton exercises the full read path against a REAL Proton account.
// It is skipped unless PROTON_USERNAME is set, so the normal `go test` run and
// CI never touch the network or need credentials.
//
// Run it yourself (keeps creds out of the agent transcript) with the `!` prefix:
//
//	! cd /home/ian/Code/Haven/rclone-android/go && \
//	  PATH=/usr/local/go/bin:$PATH GOFLAGS=-mod=mod \
//	  PROTON_USERNAME='you@proton.me' PROTON_PASSWORD='…' \
//	  go test ./mailbridge/ -run TestLiveProton -v -count=1
//
// Optional env:
//   PROTON_2FA              current 6-digit TOTP code (if 2FA is on; expires in 30s)
//   PROTON_MAILBOX_PASSWORD second password (only for two-password-mode accounts)
//   PROTON_APP_VERSION      override the appVersion (defaults to the value Haven ships)
//
// This validates exactly what the Android app does — same appVersion, same
// MbRPC calls — without the device/tunnel stack, so it isolates the "does the
// API + appVersion work on this account?" question.
func TestLiveProton(t *testing.T) {
	username := os.Getenv("PROTON_USERNAME")
	if username == "" {
		t.Skip("set PROTON_USERNAME (and PROTON_PASSWORD) to run the live Proton read test")
	}
	password := os.Getenv("PROTON_PASSWORD")
	if password == "" {
		t.Fatal("PROTON_USERNAME set but PROTON_PASSWORD missing")
	}
	appVersion := os.Getenv("PROTON_APP_VERSION")
	if appVersion == "" {
		// Mirror ProtonMailClient.APP_VERSION so we test the shipped value.
		appVersion = "macos-drive@1.0.0-alpha.1+rclone"
	}

	const sid = "live-test"

	// --- login ---
	loginReq := map[string]any{
		"sessionId":  sid,
		"username":   username,
		"password":   password,
		"appVersion": appVersion,
	}
	if v := os.Getenv("PROTON_2FA"); v != "" {
		loginReq["twoFA"] = v
	}
	if v := os.Getenv("PROTON_MAILBOX_PASSWORD"); v != "" {
		loginReq["mailboxPassword"] = v
	}
	res := call(t, "login", loginReq)
	if res.Status == 412 {
		t.Fatalf("login needs another factor: %s — set PROTON_2FA and/or PROTON_MAILBOX_PASSWORD and retry", res.Output)
	}
	if res.Status != 200 {
		t.Fatalf("login failed (status %d): %s", res.Status, res.Output)
	}
	t.Logf("login OK (appVersion=%q)", appVersion)
	defer call(t, "logout", map[string]any{"sessionId": sid})

	// --- folders ---
	res = call(t, "listFolders", map[string]any{"sessionId": sid})
	if res.Status != 200 {
		t.Fatalf("listFolders failed (status %d): %s", res.Status, res.Output)
	}
	var labels []struct {
		ID   string
		Name string
		Type int
	}
	mustJSON(t, res.Output, &labels)
	t.Logf("listFolders OK: %d folders", len(labels))
	for _, l := range labels {
		t.Logf("  folder id=%s type=%d name=%q", l.ID, l.Type, l.Name)
	}

	// --- messages in Inbox (label id "0") ---
	res = call(t, "listMessages", map[string]any{"sessionId": sid, "labelID": "0", "desc": true})
	if res.Status != 200 {
		t.Fatalf("listMessages failed (status %d): %s", res.Status, res.Output)
	}
	var metas []struct {
		ID      string
		Subject string
		Unread  int
	}
	mustJSON(t, res.Output, &metas)
	t.Logf("listMessages(Inbox) OK: %d messages", len(metas))
	for i, m := range metas {
		if i >= 5 {
			break
		}
		t.Logf("  [%d] id=%s unread=%d subject=%q", i, m.ID, m.Unread, m.Subject)
	}
	if len(metas) == 0 {
		t.Log("Inbox empty — skipping getMessage. Send the account an email and rerun to test decryption.")
		return
	}

	// --- decrypt the newest message ---
	res = call(t, "getMessage", map[string]any{"sessionId": sid, "messageID": metas[0].ID})
	if res.Status != 200 {
		t.Fatalf("getMessage failed (status %d): %s", res.Status, res.Output)
	}
	var body struct {
		RFC822 string
	}
	mustJSON(t, res.Output, &body)
	raw, err := base64.StdEncoding.DecodeString(body.RFC822)
	if err != nil {
		t.Fatalf("rfc822 base64 decode failed: %v", err)
	}
	if dump := os.Getenv("PROTON_DUMP"); dump != "" {
		if err := os.WriteFile(dump, raw, 0o600); err != nil {
			t.Logf("PROTON_DUMP write to %s failed: %v", dump, err)
		} else {
			t.Logf("wrote decrypted MIME to %s (%d bytes)", dump, len(raw))
		}
	}
	preview := raw
	if len(preview) > 400 {
		preview = preview[:400]
	}
	t.Logf("getMessage OK: decrypted %d bytes of MIME. First 400 bytes:\n%s", len(raw), string(preview))
}



func call(t *testing.T, method string, req map[string]any) *MbResult {
	t.Helper()
	in, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal %s req: %v", method, err)
	}
	return MbRPC(method, string(in))
}

func mustJSON(t *testing.T, s string, v any) {
	t.Helper()
	if err := json.Unmarshal([]byte(s), v); err != nil {
		t.Fatalf("unmarshal output %q: %v", s, err)
	}
}
