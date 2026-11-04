#!/usr/bin/env bash
#
# Starts a native binary as a node and asks it over REST for its version, which
# shows that the launcher, the bundled runtime, the resources and the servers
# all work on this platform. It also checks that the parts this release leaves
# off stay off: their commands and options are missing from the help, a node is
# refused the ledger, answers 503 on their REST paths and opens no S3 port.
# Usage: smoke-test.sh <binary> <expected version>

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
	[ -z "${help:-}" ] || printf -- '--- help output ---\n%s\n' "$help" >&2
	exit 1
}

authorized() { curl -fsSk -H "Authorization: Bearer $TOKEN" "$@"; }

# status_of <path>: the HTTP status that the node answers an authorized GET with
status_of() { curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$REST$1"; }

# listed <name>: whether a line of the help on stdin starts with the name, as
# the lines that list a command or an option do
listed() { grep -Eq -e "^[[:space:]]+([^[:space:]]+, )*$1([[:space:],=[]|\$)"; }

"$BINARY" --version | grep -qF "$VERSION" || fail "$BINARY --version does not report $VERSION"

for pair in ":bootstrap" "cli:gridspork-cosign" "util:spork-db" "daemon:--restport"; do
	words="${pair%%:*}"
	# shellcheck disable=SC2086 # the words of the command, none for the top
	help="$("$BINARY" $words --help 2>"$LOG")" || fail "$BINARY $words --help failed."
	listed "${pair#*:}" <<<"$help" || fail "$BINARY $words --help does not list ${pair#*:}."

	for name in s3 ledger storage-put gridnode-start ledger-genesis --ledger --gridnode --socks-port --preview; do
		if listed "$name" <<<"$help"; then
			fail "$BINARY $words --help lists $name."
		fi
	done
done

# The refusal comes before the node binds a port or locks its data directory
"$BINARY" daemon --no-seeds -p 52985 -r 52986 --ledger >"$LOG" 2>&1 &
readonly REFUSED=$!
for _ in $(seq 30); do
	kill -0 "$REFUSED" 2>/dev/null || break
	sleep 1
done
! kill "$REFUSED" 2>/dev/null || fail "daemon --ledger started a node."
code=0
wait "$REFUSED" || code=$?
[ "$code" = 2 ] || fail "daemon --ledger exited with $code, not 2."

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

for path in /ledger /storage /gridnode /gridspork/communication; do
	code="$(status_of "$path")"
	[ "$code" = 503 ] || fail "GET $path answered $code, not 503."
done

code="$(status_of /gridspork/mint-supply)"
[ "$code" = 200 ] || [ "$code" = 204 ] || fail "GET /gridspork/mint-supply answered $code, not 200 or 204."

code=0
curl -s -o /dev/null --max-time 15 http://localhost:52885/ || code=$?
[ "$code" = 7 ] || fail "Port 52885 of the S3 endpoint did not refuse a connection (curl exited with $code)."

authorized -X POST "$REST/stop" >/dev/null || fail "POST /stop was refused."
for _ in $(seq 30); do
	kill -0 "$NODE" 2>/dev/null || { printf 'The node answered as %s and stopped when asked.\n' "$VERSION"; exit 0; }
	sleep 1
done

fail "The node did not stop when asked."
