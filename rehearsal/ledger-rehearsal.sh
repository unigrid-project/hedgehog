#!/usr/bin/env bash
# Starts four ledger validators from the built jar on this machine, takes the spork database of an earlier build
# through the launch, sets the sporks of a private network, follows a coin from a mint to a transfer, and changes the
# board keys while the network runs. The nodes dial no seeds, trust board keys made here and bind to the loopback, so
# nothing touches the real network.
# Usage: rehearsal/ledger-rehearsal.sh [snapshot]
# The snapshot defaults to ~/hedgehog-release/bootstrap.dat. A copy of it is made and signed with a test key, because
# a node uses only a snapshot that its own board keys have signed. The hash in the genesis does not change by that.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SNAPSHOT=${1:-$HOME/hedgehog-release/bootstrap.dat}
# Mints that a build from before co-signatures stored, signed once by a key that is no board key of this network
EARLIER=$ROOT/application/src/test/resources/spork/legacy-spork.db
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
[ -f "$EARLIER" ] || fail "no spork database of an earlier build at $EARLIER"

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

# on_node <n> <command>: a command that the operator of node n runs on its machine, in the data directory of the node
on_node() {
	local n=$1
	shift
	XDG_DATA_HOME="$WORK/n$n" "$JAVA" -jar "$JAR" "$@" 2> >(grep -v '^WARNING\|^SLF4J' >&2)
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

# exits_with <status> <file> <command...>: runs the command with its output in the file, fails when it exits otherwise.
# What the command writes on its error stream can reach the file a moment after it exits.
exits_with() {
	local expected=$1 output=$2 status=0
	shift 2
	"$@" > "$output" 2>&1 || status=$?
	[ "$status" -eq "$expected" ] || fail "${*:1:4} exited with $status, not $expected; see $output"
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
# A board member keeps what util key-generate writes in a file that only they can read
(umask 077 && hedgehog util key-generate > board1.key && hedgehog util key-generate > board2.key)
BOARD1_PRIVATE=$(field 'Private Key' board1.key)
BOARD2_PRIVATE=$(field 'Private Key' board2.key)
BOARD1_PUBLIC=$(field 'Public Key' board1.key)
BOARD2_PUBLIC=$(field 'Public Key' board2.key)
NETWORK_KEYS="$BOARD1_PUBLIC,$BOARD2_PUBLIC"
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

# start_node <n>: starts node n, adding to its log. A node logs its warnings, such as a spork it leaves out.
start_node() {
	local n=$1
	XDG_DATA_HOME="$WORK/n$n" HEDGEHOG_REST_TOKEN="token$n" "$JAVA" -jar "$JAR" daemon --no-seeds -H 127.0.0.1 \
		-p $((P2P_BASE + n)) -r $((REST_BASE + n)) --network-keys "$NETWORK_KEYS" --ledger \
		--ledger-genesis "$WORK/genesis.json" --ledger-key "$WORK/v$n.key" -vv >> "n$n.log" 2>&1 &
	PIDS[n]=$!
}

stopped() { ! kill -0 "${PIDS[$1]}" 2>/dev/null; }

# The last node runs on the spork database of an earlier build, as a node that was upgraded does
log "starting the nodes, node $NODES on the spork database of an earlier build"
for n in $(seq "$NODES"); do
	mkdir -p "n$n/hedgehog"
	ln -sf "$WORK/snapshot.dat" "n$n/hedgehog/bootstrap.dat"
	[ "$n" -ne "$NODES" ] || cp "$EARLIER" "n$n/hedgehog/spork.db"
	: > "n$n.log"
	start_node "$n"
done

signing() { node "$1" ledger info | grep -q '"running" : true' && node "$1" ledger info | grep -q '"signing" : true'; }
for n in $(seq "$NODES"); do
	until_true 120 "node $n to run and sign" signing "$n"
done

log "linking the nodes"
for n in $(seq 2 "$NODES"); do
	node "$n" node-add "127.0.0.1:$((P2P_BASE + 1))" > /dev/null
done

# The launch as the operator runs it. The spork database of an earlier build holds mints that a key signed which is no
# board key, so a node leaves them out on start. Two board members sign it one after the other, each with the key file
# that util key-generate wrote for them, and it is installed on node 1 while the node is stopped.
# Its mints sit below the tip of the snapshot: the snapshot balances hold them, and they never mint on the ledger.
log "the launch: node $NODES leaves out the sporks of the earlier build and keeps a copy of its database"
until_true 60 "node $NODES to keep a copy of the earlier database" compgen -G "n$NODES/hedgehog/spork.db.unsigned-*"
KEPT=$(compgen -G "n$NODES/hedgehog/spork.db.unsigned-*")
cmp -s "$EARLIER" "$KEPT" || fail "the copy that node $NODES kept is not the spork database of the earlier build"
for type in MINT_STORAGE MINT_SUPPLY; do
	LEFT_OUT="The stored $type spork is left out: two different board keys did not sign it."
	LEFT_OUT+=" The file as it was is kept as $WORK/$KEPT"
	until_true 5 "node $NODES to log that it left out the $type spork" grep -qF "$LEFT_OUT" "n$NODES.log"
done

log "the launch: the earlier database signed by board keys 1 and 2, one after the other"
cp "$EARLIER" spork.db.bak
exits_with 1 validate-before.txt hedgehog util spork-db validate spork.db.bak --network-keys "$NETWORK_KEYS"
exits_with 0 sign-once.txt hedgehog util spork-db sign spork.db.bak --output once.db --key-file board1.key \
	--network-keys "$NETWORK_KEYS"
exits_with 0 sign-twice.txt hedgehog util spork-db sign once.db --output twice.db --key-file board2.key \
	--network-keys "$NETWORK_KEYS"
exits_with 0 validate-after.txt hedgehog util spork-db validate twice.db --network-keys "$NETWORK_KEYS"
cmp -s "$EARLIER" spork.db.bak || fail "signing changed the spork database of the earlier build"

# shown_data <type> <file>: the entries of the data of a spork as util spork-db sign showed it, one to a line
shown_data() {
	awk -v data="$1 data:" -v previous="$1 previous data:" '$0 == previous { on = 0 }
		on && /:/ { gsub(/^ +|,$/, ""); print } $0 == data { on = 1 }' "$2" | sort
}

digest_of() { sed -n "s/^$1: .*, digest \([0-9a-f]*\), .*/\1/p" "$2"; }

for type in MINT_STORAGE MINT_SUPPLY; do
	grep -q "^$type: .*, not signed by the board, content valid$" validate-before.txt \
		|| fail "util spork-db validate did not report the earlier $type spork as not signed by the board"
	grep -q "^$type: .*, signed by the board, content valid$" validate-after.txt \
		|| fail "util spork-db validate did not report the $type spork as signed by the board after two runs"
	[ -n "$(shown_data "$type" sign-once.txt)" ] || fail "util spork-db sign showed no data of the $type spork"
	[ -n "$(digest_of "$type" validate-after.txt)" ] || fail "util spork-db validate showed no digest of $type"
done

# A node that holds no sporks writes the same database at every save, so what node 1 holds is known once it saved
log "the launch: the import refuses while node 1 runs"
until_true 240 "the first save of the spork database of node 1" test -f n1/hedgehog/spork.db
[ -f n1/hedgehog/spork.db.lock ] || fail "node 1 runs without a spork.db.lock"
HELD=$(sha256sum < n1/hedgehog/spork.db)
exits_with 2 import-refused.txt on_node 1 util spork-db import twice.db --network-keys "$NETWORK_KEYS"
until_true 5 "the import to say that the spork database of node 1 is in use" grep -qF \
	"Nothing was imported: the spork database in $WORK/n1/hedgehog is in use" import-refused.txt
[ "$(sha256sum < n1/hedgehog/spork.db)" = "$HELD" ] || fail "the refused import changed the spork database of node 1"
! compgen -G "n1/hedgehog/spork.db.[0-9]*" > /dev/null || fail "the refused import kept a backup on node 1"

log "the launch: node 1 stopped, the signed database imported and node 1 started again"
node 1 stop > /dev/null
until_true 60 "node 1 to stop" stopped 1
STOPPED=$(sha256sum < n1/hedgehog/spork.db)
exits_with 0 import.txt on_node 1 util spork-db import twice.db --network-keys "$NETWORK_KEYS"
BACKUP=$(field backup import.txt)
[ -f "$BACKUP" ] && [ "$(sha256sum < "$BACKUP")" = "$STOPPED" ] \
	|| fail "the import kept no backup of the spork database that node 1 held"
grep -qxF "imported: $WORK/n1/hedgehog/spork.db" import.txt || fail "the import installed no database on node 1"
start_node 1
until_true 120 "node 1 to run and sign again" signing 1

launch_counts() {
	node "$1" gridspork-get mint-storage | grep -q '"counting" : true' \
		&& node "$1" gridspork-get mint-supply | grep -q '"counting" : true'
}

# held_data <file>: the entries of the data of a spork as gridspork-get printed it, one to a line
held_data() {
	awk 'on && match($0, /[^ ]/) == indent && /^ *[}]/ { on = 0 } on && /:/ { gsub(/^ +|,$/, ""); print }
		/^ *"data" : [{]$/ { on = 1; indent = match($0, /[^ ]/) }' "$1" | sort
}

# head_of <type> <key> <file>: a value of the head of a spork as gridspork-log printed it
head_of() {
	awk -v type="\"$1\" : {" -v key="^ *\"$2\" : " 'index($0, type) { found = 1 } found && /"head" : [{]/ { head = 1 }
		head && $0 ~ key { sub(/^[^:]*: "?/, ""); sub(/"?,?$/, ""); print; exit }' "$3"
}

log "the launch: the mints of the earlier build count on every node, as board keys 1 and 2 signed them"
for n in $(seq "$NODES"); do
	until_true 300 "the sporks of the launch to count on node $n" launch_counts "$n"
	node "$n" gridspork-log > "signers-n$n.json"
	for spork in mint-storage mint-supply; do
		type=${spork^^}
		type=${type//-/_}
		node "$n" gridspork-get "$spork" > "launch-n$n-$spork.json"
		[ "$(head_of "$type" signer "signers-n$n.json")" = "$BOARD1_PUBLIC" ] \
			&& [ "$(head_of "$type" cosigner "signers-n$n.json")" = "$BOARD2_PUBLIC" ] \
			|| fail "node $n holds a $type spork that board keys 1 and 2 did not sign"
		[[ $(head_of "$type" digest "signers-n$n.json") == "$(digest_of "$type" validate-after.txt)"* ]] \
			|| fail "node $n holds another $type spork than the one that the board signed"
		grep -q '"counting" : true' "launch-n$n-$spork.json" || fail "the $type spork does not count on node $n"
		[ "$(held_data "launch-n$n-$spork.json")" = "$(shown_data "$type" sign-once.txt)" ] \
			|| fail "node $n holds the $type spork with other data than the database of the earlier build"
	done
done

# What the foundation does: two board keys, the second co-signing what the first proposes. The launch set the mint
# supply and the mints, which the mint of alice grows; the fee and the blacklist are the first versions of their sporks.
log "setting the sporks"
: > blacklist.txt
node 1 gridspork-set ledger-fee -D 0.001 -k "$BOARD1_PRIVATE" --first-version > /dev/null
node 1 gridspork-set ledger-blacklist -D "$WORK/blacklist.txt" -k "$BOARD1_PRIVATE" --first-version > /dev/null
node 1 gridspork-grow mint-storage --address "$(cat alice.pub)" --time 1577836800 --allow-past -D 1000 \
	-k "$BOARD1_PRIVATE" > /dev/null
DIGESTS=$(node 1 gridspork-pending | awk '/"digest" :/ { gsub(/[",]/, "", $3); print $3 }')
[ "$(echo "$DIGESTS" | wc -w)" -eq 3 ] || fail "expected three proposals, found: $DIGESTS"
# shellcheck disable=SC2086
node 1 gridspork-cosign -k "$BOARD2_PRIVATE" --first-version LEDGER_FEE --first-version LEDGER_BLACKLIST $DIGESTS \
	> /dev/null

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
(umask 077 && hedgehog util key-generate > board3.key)
BOARD3_PRIVATE=$(field 'Private Key' board3.key)
BOARD3_PUBLIC=$(field 'Public Key' board3.key)
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

log "PASS: the sporks of an earlier build launched on every node, $NODES validators, one chain, mint and transfers"\
" seen on every node, the board keys changed on every node"
