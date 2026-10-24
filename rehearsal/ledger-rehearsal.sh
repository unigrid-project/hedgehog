#!/usr/bin/env bash
# Starts four ledger validators from the built jar on this machine, sets the sporks of a private network, follows
# a coin from a mint to a transfer, and changes the board keys while the network runs. The nodes dial no seeds, trust
# board keys made here and bind to the loopback, so nothing touches the real network.
# Usage: rehearsal/ledger-rehearsal.sh [snapshot]
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

# The command line keeps a record of the board keys it verified in its data directory, which is one of the rehearsal
hedgehog() { XDG_DATA_HOME="$WORK/cli" "$JAVA" -jar "$JAR" "$@" 2> >(grep -v '^WARNING\|^SLF4J' >&2); }

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
mkdir -p "$WORK"
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

# What the foundation does: two board keys, the second co-signing what the first proposes. The network holds no
# sporks yet, so each is the first version of its spork.
log "setting the sporks"
: > blacklist.txt
node 1 gridspork-set mint-supply -D 1000000000 -k "$BOARD1_PRIVATE" --first-version > /dev/null
node 1 gridspork-set ledger-fee -D 0.001 -k "$BOARD1_PRIVATE" --first-version > /dev/null
node 1 gridspork-set ledger-blacklist -D "$WORK/blacklist.txt" -k "$BOARD1_PRIVATE" --first-version > /dev/null
node 1 gridspork-grow mint-storage --address "$(cat alice.pub)" --time 1577836800 --allow-past -D 1000 \
	-k "$BOARD1_PRIVATE" --first-version > /dev/null
DIGESTS=$(node 1 gridspork-pending | awk '/"digest" :/ { gsub(/[",]/, "", $3); print $3 }')
[ "$(echo "$DIGESTS" | wc -w)" -eq 4 ] || fail "expected four proposals, found: $DIGESTS"
# shellcheck disable=SC2086
node 1 gridspork-cosign -k "$BOARD2_PRIVATE" --first-version MINT_SUPPLY --first-version LEDGER_FEE \
	--first-version LEDGER_BLACKLIST --first-version MINT_STORAGE $DIGESTS > /dev/null

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

# A change of the board keys while the network runs: a third key comes in and the first is switched off. The two keys
# of the board approve it, and so does the key it adds; a key that the change keeps proposes it and the new key
# co-signs it. The change follows on the chain of a spork database of a node, which holds none yet, so the first save
# of one is waited for.
log "changing the board keys: board key 3 comes in, board key 1 is switched off"
hedgehog util key-generate > board3.txt
BOARD1_PUBLIC=$(field 'Public Key' board1.txt)
BOARD3_PRIVATE=$(field 'Private Key' board3.txt)
BOARD3_PUBLIC=$(field 'Public Key' board3.txt)
until_true 240 "the first save of the spork database of node 1" test -f n1/hedgehog/spork.db
cp n1/hedgehog/spork.db before-the-change.db
hedgehog util board-keys propose --network-keys "$NETWORK_KEYS" --spork-db before-the-change.db \
	--activate "$BOARD3_PUBLIC" --deactivate "$BOARD1_PUBLIC" --output change.json > /dev/null
for key in "$BOARD1_PRIVATE" "$BOARD2_PRIVATE" "$BOARD3_PRIVATE"; do
	hedgehog util board-keys approve change.json --spork-db before-the-change.db --network-keys "$NETWORK_KEYS" \
		-k "$key" > /dev/null
done

# The sporks that board key 1 signed count for nothing from the change on, until keys that stay sign them again
node 1 gridspork-set board-keys -D "$WORK/change.json" --first-version --allow-uncounted -k "$BOARD2_PRIVATE" \
	> /dev/null
DIGESTS=$(node 1 gridspork-pending | awk '/"digest" :/ { gsub(/[",]/, "", $3); print $3 }')
[ "$(echo "$DIGESTS" | wc -w)" -eq 1 ] || fail "expected the change of the board keys to wait, found: $DIGESTS"
# shellcheck disable=SC2086
node 1 gridspork-cosign -k "$BOARD3_PRIVATE" --first-version BOARD_KEYS $DIGESTS > /dev/null

goes_by_the_new_board() {
	local shown
	shown=$(node "$1" gridspork-get board-keys) || return 1
	grep -q "^  \"active\" : .*$BOARD3_PUBLIC" <<< "$shown" && grep -q "^  \"inactive\" : .*$BOARD1_PUBLIC" <<< "$shown"
}
for n in $(seq "$NODES"); do
	until_true 120 "the change of the board keys on node $n" goes_by_the_new_board "$n"
done

log "the sporks signed again by keys that stay, the new key among them"
SWITCHED_OFF=(--switched-off MINT_STORAGE --switched-off MINT_SUPPLY --switched-off LEDGER_BLACKLIST
	--switched-off LEDGER_FEE)
node 1 gridspork-renew -k "$BOARD3_PRIVATE" "${SWITCHED_OFF[@]}" > /dev/null
DIGESTS=$(node 1 gridspork-pending | awk '/"digest" :/ { gsub(/[",]/, "", $3); print $3 }')
[ "$(echo "$DIGESTS" | wc -w)" -eq 5 ] || fail "expected five renewals, found: $DIGESTS"
# shellcheck disable=SC2086
node 1 gridspork-cosign -k "$BOARD2_PRIVATE" "${SWITCHED_OFF[@]}" $DIGESTS > /dev/null

fee_counts() { node "$1" gridspork-get ledger-fee | grep -q '"counting" : true'; }
for n in $(seq "$NODES"); do
	until_true 120 "the fee signed again on node $n" fee_counts "$n"
done

log "a transfer under the sporks that the new board signed"
node 2 ledger transfer --key alice.key --to "$(cat bob.pub)" --amount 5 > /dev/null
for n in $(seq "$NODES"); do
	until_true 120 "the second transfer on node $n" balance_is "$n" "$(cat bob.pub)" 15.00000000
done

log "the switched-off board key signs nothing"
if node 1 gridspork-set ledger-fee -D 0.002 -k "$BOARD1_PRIVATE" > refused.txt 2>&1; then
	fail "the switched-off board key 1 proposed a spork"
fi
until_true 5 "the refusal of board key 1" grep -q "not one of the current board keys" refused.txt

log "every node is on one chain"
CHAIN=$(sed -n 's/^chainId: //p' genesis.txt)
for n in $(seq "$NODES"); do
	node "$n" ledger info | grep -q "$CHAIN" || fail "node $n is on another chain than the genesis"
done

log "PASS: $NODES validators, one chain, mint and transfers seen on every node, the board keys changed on every node"
