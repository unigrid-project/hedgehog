#!/usr/bin/env bash
#
# Starts a native binary as a node and asks it over REST for its version, which
# shows that the launcher, the bundled runtime, the resources and the servers
# all work on this platform. Usage: smoke-test.sh <binary> <expected version>

set -euo pipefail

readonly BINARY="$1"
readonly VERSION="$2"
readonly TOKEN=smoke-test
# The node serves REST over TLS with a certificate of its own making, hence curl -k.
readonly REST=https://localhost:52984
readonly LOG="$(mktemp)"

fail() {
	printf '%s\n' "$*" >&2
	printf -- '--- node output ---\n' >&2
	cat "$LOG" >&2
	exit 1
}

authorized() { curl -fsSk -H "Authorization: Bearer $TOKEN" "$@"; }

"$BINARY" --version | grep -qF "$VERSION" || fail "$BINARY --version does not report $VERSION"

"$BINARY" daemon --no-seeds -p 52983 -r 52984 --resttoken "$TOKEN" >"$LOG" 2>&1 &
readonly NODE=$!
trap 'kill "$NODE" 2>/dev/null || true' EXIT

answer=""
for _ in $(seq 60); do
	answer="$(authorized "$REST/version" 2>/dev/null)" && break
	kill -0 "$NODE" 2>/dev/null || fail "The node exited before it answered."
	sleep 1
done

[[ "$answer" == *"\"version\":\"$VERSION\""* ]] || fail "GET /version answered '$answer', not $VERSION."

[ "$(curl -sk -o /dev/null -w '%{http_code}' "$REST/version")" = 401 ] || fail "GET /version answered without a token."

authorized -X POST "$REST/stop" >/dev/null || fail "POST /stop was refused."
for _ in $(seq 30); do
	kill -0 "$NODE" 2>/dev/null || { printf 'The node answered as %s and stopped when asked.\n' "$VERSION"; exit 0; }
	sleep 1
done

fail "The node did not stop when asked."
