#!/usr/bin/env bash
# Guard against the "R8 renamed a reflectively-loaded class" class of bug — the
# kind that ships a feature 100% broken in *release* builds while every *debug*
# test passes, because debug doesn't run R8. The motivating case: JavaMail's
# getStore("imaps") threw NoSuchProviderException because R8 renamed
# com.sun.mail.imap.IMAPSSLStore -> s4.c, breaking every email account in
# release builds (fixed in v5.59.45).
#
# After R8 runs on the release variant, assert that every class we load
# reflectively by fully-qualified name kept that name (identity mapping in
# mapping.txt). A renamed or removed class means a -keep rule in
# app/proguard-rules.pro is missing or broke.
#
# Usage: scripts/check-r8-kept-classes.sh [path/to/mapping.txt]
#   With no argument it auto-locates the arm64Release mapping. The standalone
#   :app:minifyArm64ReleaseWithR8 task (what CI runs) writes to the intermediates
#   path; a full assemble also copies one to outputs/. Prefer the freshest.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -ge 1 ]; then
  MAP="$1"
else
  MAP=""
  for cand in \
    app/build/intermediates/mapping/arm64Release/minifyArm64ReleaseWithR8/mapping.txt \
    app/build/outputs/mapping/arm64Release/mapping.txt; do
    if [ -f "$cand" ]; then
      if [ -z "$MAP" ] || [ "$cand" -nt "$MAP" ]; then MAP="$cand"; fi
    fi
  done
  [ -n "$MAP" ] || MAP="app/build/intermediates/mapping/arm64Release/minifyArm64ReleaseWithR8/mapping.txt"
fi
LIST="scripts/r8-must-keep-classes.txt"
IMAP_CLIENT="core/mail/src/main/kotlin/sh/haven/core/mail/ImapMailClient.kt"

if [ ! -f "$MAP" ]; then
  echo "✖ mapping.txt not found at: $MAP" >&2
  echo "  Run ./gradlew :app:minifyArm64ReleaseWithR8 first." >&2
  exit 2
fi

# Curated list (one FQN per line; #-comments and blanks ignored).
fqns=()
while IFS= read -r line; do
  [ -n "$line" ] && fqns+=("$line")
done < <(grep -vE '^\s*(#|$)' "$LIST" || true)

# Auto-derive the JavaMail provider classes our code loads via mail.<proto>.class,
# so the email path stays checked even if the curated list isn't updated.
if [ -f "$IMAP_CLIENT" ]; then
  while IFS= read -r c; do
    [ -n "$c" ] && fqns+=("$c")
  done < <(grep -oE '"mail\.[a-z]+\.class"\] = "[^"]+"' "$IMAP_CLIENT" \
             | grep -oE '"[A-Za-z0-9_.]+"$' | tr -d '"' || true)
fi

# Deduplicate.
mapfile -t fqns < <(printf '%s\n' "${fqns[@]}" | sort -u)

failures=()
for fqn in "${fqns[@]}"; do
  [ -n "$fqn" ] || continue
  esc=${fqn//./\\.}
  if grep -qE "^${esc} -> ${esc}:" "$MAP"; then
    continue  # identity-mapped == kept
  fi
  actual=$(grep -E "^${esc} -> " "$MAP" | head -1 || true)
  if [ -n "$actual" ]; then
    failures+=("$fqn  →  RENAMED to ${actual##*-> }")
  else
    failures+=("$fqn  →  ABSENT (shrunk away or never compiled in)")
  fi
done

if [ ${#failures[@]} -gt 0 ]; then
  echo "✖ ${#failures[@]} reflectively-loaded class(es) did NOT survive R8 intact:"
  printf '   %s\n' "${failures[@]}"
  echo
  echo "These are resolved at runtime by string name (Class.forName / JavaMail"
  echo "mail.<proto>.class / JNI). A rename breaks them in release builds only."
  echo "Add or fix a -keep rule in app/proguard-rules.pro, then re-run."
  exit 1
fi

echo "✓ All ${#fqns[@]} reflectively-loaded classes kept their names through R8."
