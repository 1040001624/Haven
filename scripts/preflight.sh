#!/usr/bin/env bash
# preflight: run CI's fast `checks`-job gates locally in one command, so a
# deterministic failure (hardcoded string, stale translation export, missing
# changelog) is caught before push instead of after a ~13-min CI round-trip.
#
# Mirrors the `checks` job in .github/workflows/ci.yml exactly. It does NOT
# run lint (MissingTranslation etc. — slow, per-module) or the unit tests;
# see scripts/README or the memory note on gates. Fast: a few seconds.
#
# Usage:
#   scripts/preflight.sh            # all gates (changelog gate is release-only, see below)
#   scripts/preflight.sh --no-changelog   # skip the changelog gate (WIP push, not a release)
#
# Exit non-zero if any gate fails; each prints what to do.

set -uo pipefail
cd "$(dirname "$0")/.."

fail=0
run() {
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        echo "✓ $desc"
    else
        echo "✗ $desc" >&2
        fail=1
    fi
}

skip_changelog=0
[ "${1:-}" = "--no-changelog" ] && skip_changelog=1

# 1. Hardcoded UI strings (#210) — literal Text("…")/title=/subtitle=.
run "no hardcoded UI strings" ./scripts/check-i18n-hardcoded.sh

# 2. Translation export current — regenerate and fail if docs/i18n/strings.json
#    drifted from the strings.xml sources. This is the gate that reddened CI on
#    #359: strings added/translated but the export not regenerated + committed.
python3 scripts/i18n_export.py >/dev/null 2>&1
if git diff --quiet -- docs/i18n/strings.json; then
    echo "✓ translation export current"
else
    echo "✗ translation export drifted — commit the regenerated docs/i18n/strings.json:" >&2
    echo "    git add docs/i18n/strings.json" >&2
    fail=1
fi

# 3. Changelog has notes for the current version (release gate). Skippable for
#    a WIP push that isn't cutting a release.
if [ "$skip_changelog" -eq 0 ]; then
    run "changelog has notes for current version" ./scripts/check-changelog.sh
else
    echo "· changelog gate skipped (--no-changelog)"
fi

if [ "$fail" -ne 0 ]; then
    echo "" >&2
    echo "preflight FAILED — fix the above before pushing." >&2
    exit 1
fi
echo "preflight OK"
exit 0
