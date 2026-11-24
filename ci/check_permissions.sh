#!/usr/bin/env bash
# Permission gate for kapan.
#
# kapan requests exactly one Android permission, tied to its only feature
# (see AndroidManifest.xml comments):
#   INTERNET - fetch the public listings from vanipy.com for cache + widget
# Anything else fails the release loudly so the drift is re-reviewed.
set -euo pipefail

AAPT="$1"; APK="$2"

allowed="android.permission.INTERNET"

dump="$("$AAPT" dump permissions "$APK")"
while IFS= read -r line; do
    [ -z "$line" ] && continue
    name="$(printf '%s' "$line" | sed "s/.*name='\([^']*\)'.*/\1/")"
    if ! printf '%s\n' "$allowed" | grep -qxF "$name"; then
        echo "unexpected permission requested: $name"
        exit 1
    fi
done <<EOF
$(printf '%s\n' "$dump" | grep 'uses-permission' || true)
EOF

for need in $allowed; do
    if ! printf '%s\n' "$dump" | grep -qF "name='${need}'"; then
        echo "expected permission missing from APK: $need"
        exit 1
    fi
done

echo "permission gate: exactly the one declared permission is requested"
