#!/usr/bin/env bash
# Starts four ledger validators from the built jar on this machine, sets the sporks of a private network and follows
# a coin from a mint to a transfer. The nodes dial no seeds, trust board keys made here and bind to the loopback, so
# nothing touches the real network. Usage: rehearsal/ledger-rehearsal.sh [snapshot]
# The snapshot defaults to ~/hedgehog-release/bootstrap.dat. A copy of it is made and signed with a test key, because
# a node uses only a snapshot that its own board keys have signed. The hash in the genesis does not change by that.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SNAPSHOT=${1:-$HOME/hedgehog-release/bootstrap.dat}
WORK=${REHEARSAL_DIR:-$(mktemp -d)}
P2P_BASE=${P2P_BASE:-53000}
REST_BASE=${REST_BASE:-53100}
NODES=4
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
PIDS=()

log() { printf '== %s\n' "$*"; }
fail() { printf 'FAIL: %s\nlogs and keys are in %s\n' "$*" "$WORK" >&2; exit 1; }

cleanup() {
	[ ${#PIDS[@]} -eq 0 ] || kill "${PIDS[@]}" 2>/dev/null || true
}
trap cleanup EXIT

[ -f "$SNAPSHOT" ] || fail "no snapshot at $SNAPSHOT"

JAR=$(ls -t "$ROOT"/application/target/hedgehog-*-jar-with-dependencies.jar 2>/dev/null | head -1 || true)
if [ -z "$JAR" ]; then
	log "building the jar"
	(cd "$ROOT" && mvn -o -q -pl application -am package -DskipTests)
	JAR=$(ls -t "$ROOT"/application/target/hedgehog-*-jar-with-dependencies.jar | head -1)
fi

hedgehog() { "$JAVA" -jar "$JAR" "$@" 2> >(grep -v '^WARNING\|^SLF4J' >&2); }

# node <n> <command>: a cli command against node n. The board keys are those of this network, as a spork is signed by
# the command line, which checks the key it is given against them.
node() {
	local n=$1
	shift
	hedgehog cli --resthost 127.0.0.1 --restport $((REST_BASE + n)) --resttoken "token$n" \
		--network-keys "$NETWORK_KEYS" "$@"
}

field() { sed -n "s/^$1: //p" "$2"; }

# until <seconds> <description> <command...>: polls once a second, fails when the time is up
until_true() {
	local seconds=$1 what=$2
	shift 2
	for _ in $(seq "$seconds"); do
		"$@" >/dev/null 2>&1 && return 0
		sleep 1
	done
	fail "timed out waiting for $what"
}

# Cuts the signature off the end of a snapshot, so that it can be signed with a key of this network
strip_signature() {
	local file=$1 size window=1024 offset
	size=$(stat -c %s "$file")
	offset=$(tail -c "$window" "$file" | LC_ALL=C grep -obUaP 'UGDSIGN1' | tail -1 | cut -d: -f1 || true)
	[ -z "$offset" ] || truncate -s $((size - window + offset)) "$file"
}

log "keys in $WORK"
cd "$WORK"
hedgehog util key-generate > board1.txt
hedgehog util key-generate > board2.txt
BOARD1_PRIVATE=$(field 'Private Key' board1.txt)
BOARD2_PRIVATE=$(field 'Private Key' board2.txt)
NETWORK_KEYS="$(field 'Public Key' board1.txt),$(field 'Public Key' board2.txt)"
hedgehog util ledger-key-generate alice.key > alice.pub
hedgehog util ledger-key-generate bob.key > bob.pub

log "snapshot signed with a test key"
cp "$SNAPSHOT" snapshot.dat
strip_signature snapshot.dat
hedgehog bootstrap sign -s "$WORK/snapshot.dat" -k "$BOARD1_PRIVATE" --network-keys "$NETWORK_KEYS" > /dev/null

log "genesis of $NODES validators"
VALIDATORS=()
for n in $(seq "$NODES"); do
	hedgehog util ledger-key-generate "v$n.key" > "v$n.pub"
	VALIDATORS+=(--validator "$(cat "v$n.pub")")
done
hedgehog util ledger-genesis "$WORK/snapshot.dat" --network-keys "$NETWORK_KEYS" "${VALIDATORS[@]}" \
	--time "$(date +%s%3N)" --round-millis 3600000 --output genesis.json | tee genesis.txt

log "starting the nodes"
for n in $(seq "$NODES"); do
	mkdir -p "n$n/hedgehog"
	ln -sf "$WORK/snapshot.dat" "n$n/hedgehog/bootstrap.dat"
	XDG_DATA_HOME="$WORK/n$n" HEDGEHOG_REST_TOKEN="token$n" "$JAVA" -jar "$JAR" daemon --no-seeds -H 127.0.0.1 \
		-p $((P2P_BASE + n)) -r $((REST_BASE + n)) --network-keys "$NETWORK_KEYS" --ledger \
		--ledger-genesis "$WORK/genesis.json" --ledger-key "$WORK/v$n.key" > "n$n.log" 2>&1 &
	PIDS+=($!)
done

signing() { node "$1" ledger info | grep -q '"running" : true' && node "$1" ledger info | grep -q '"signing" : true'; }
for n in $(seq "$NODES"); do
	until_true 120 "node $n to run and sign" signing "$n"
done

log "linking the nodes"
for n in $(seq 2 "$NODES"); do
	node "$n" node-add "127.0.0.1:$((P2P_BASE + 1))" > /dev/null
done

# What the foundation does: two board keys, the second co-signing what the first proposes
log "setting the sporks"
: > blacklist.txt
node 1 gridspork-set mint-supply -D 1000000000 -k "$BOARD1_PRIVATE" > /dev/null
node 1 gridspork-set ledger-fee -D 0.001 -k "$BOARD1_PRIVATE" > /dev/null
node 1 gridspork-set ledger-blacklist -D "$WORK/blacklist.txt" -k "$BOARD1_PRIVATE" > /dev/null
node 1 gridspork-grow mint-storage --address "$(cat alice.pub)" --time 1577836800 --allow-past -D 1000 \
	-k "$BOARD1_PRIVATE" > /dev/null
DIGESTS=$(node 1 gridspork-pending | awk '/"digest" :/ { gsub(/[",]/, "", $3); print $3 }')
[ "$(echo "$DIGESTS" | wc -w)" -eq 4 ] || fail "expected four proposals, found: $DIGESTS"
# shellcheck disable=SC2086
node 1 gridspork-cosign -k "$BOARD2_PRIVATE" $DIGESTS > /dev/null

balance_is() { node "$1" ledger balance "$2" | grep -q "\"balance\" : \"$3\""; }

log "the mint reaches the ledger of every node"
for n in $(seq "$NODES"); do
	until_true 120 "the mint on node $n" balance_is "$n" "$(cat alice.pub)" 1000.00000000
done

log "a transfer with its fee"
node 3 ledger transfer --key alice.key --to "$(cat bob.pub)" --amount 10 > /dev/null
for n in $(seq "$NODES"); do
	until_true 120 "the transfer on node $n" balance_is "$n" "$(cat bob.pub)" 10.00000000
	balance_is "$n" "$(cat alice.pub)" 989.99900000 || fail "node $n holds another balance for the sender"
done

log "every node is on one chain"
CHAIN=$(sed -n 's/^chainId: //p' genesis.txt)
for n in $(seq "$NODES"); do
	node "$n" ledger info | grep -q "$CHAIN" || fail "node $n is on another chain than the genesis"
done

log "PASS: $NODES validators, one chain, mint and transfer seen on every node"
