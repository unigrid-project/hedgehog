# Ledger rehearsal

`ledger-rehearsal.sh` runs four ledger validators from the built jar on one machine and checks the whole path:
keys, genesis, sporks, a mint reaching every node, and a transfer with its fee. It then changes the board keys:
a third key comes in and the first is switched off, the change reaches every node, keys that stay sign the sporks
again, a second transfer goes through under them, and the switched-off key signs nothing. It builds the jar when
there is none.

Before the sporks are set, it rehearses the launch with the spork database of an earlier build, the test fixture
`legacy-spork.db`: node 4 starts on it and leaves its sporks out, two board keys sign it with `util spork-db sign
--key-file`, `util spork-db import` refuses while node 1 runs and installs it once node 1 is stopped, and every node
then holds both mint sporks as the board signed them.

    JAVA_HOME=~/.jdks/jdk-25.0.3+9 rehearsal/ledger-rehearsal.sh [snapshot]

The snapshot defaults to `~/hedgehog-release/bootstrap.dat`. It is copied, not changed. The nodes dial no seeds,
trust board keys that the script makes and listen on the loopback, ports 53001-53004 (peer-to-peer) and
53101-53104 (REST), so it never touches the real network. Set `REHEARSAL_DIR` to keep the files in a folder of
your choice, otherwise a temporary one is used; its path is printed when the script fails.

A node uses a snapshot only if one of its own board keys signed it, so the script cuts the signature of the
published snapshot off the copy and signs it with a test key. The genesis commits to the content, which is
unchanged.
