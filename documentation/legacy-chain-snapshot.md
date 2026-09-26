# Legacy chain snapshot

The Unigrid foundation ran a UTXO chain before Hedgehog existed, and that chain's `blk*.dat` files
are the only record of who held what. This part of the codebase turns those files into a single
memory-mapped snapshot of every address, its balance and its dated transaction history, signs that
snapshot with a foundation key, and lets anyone who downloads it verify the signature before
trusting the contents. The commands live under
`application/src/main/java/org/unigrid/hedgehog/command/bootstrap/`, the conversion and file format
under `application/src/main/java/org/unigrid/hedgehog/model/bootstrap/`, and the shared
`-s`/`--snapshot` option, inherited by every subcommand, in
`application/src/main/java/org/unigrid/hedgehog/command/option/SnapshotOptions.java`.
The command tree itself, the picocli conventions it follows and the `NetOptions` mixin it shares
with the rest of the CLI are covered in [Architecture overview](architecture.md); signing and key
trust reuse the same `Signature`/`NetworkKey` machinery documented in full in [Grid sporks](sporks.md).

A converted run of the actual legacy data directory produces a snapshot with these figures:

| Fact | Value |
| --- | --- |
| Active chain tip height | 3,172,666 |
| Active chain tip hash | `ffa055384cc3d357e7f3e424464f62f538f0973fc54d48eade8c5bb8ab747b52` |
| Addresses in the snapshot | 28,431 |
| Ledger entries | 16,799,121 |
| Total unspent | 15,900,032.04557988 |
| Minted into the zerocoin pool | 67,961 |
| Addresses still holding coins | 3,296 (the rest are kept only for their history) |
| Snapshot file size | 556,361,944 bytes |
| Stored blocks | 3,318,609 |
| Stale blocks discarded | 145,942 |

## Why the chain is rebuilt rather than read off the files in order

The legacy daemon stores every block it ever saw, including blocks on branches that ultimately
lost. In this run, 145,942 of the 3,318,609 stored blocks are on such a losing branch. Reading the
files in the order they were written credits and debits balances using blocks that the network
never settled on, so the totals would be wrong. Instead the converter treats the block files purely
as a bag of blocks, links every one of them to its parent by hash, and keeps only the path from the
genesis block to whichever block turned out to be deepest — the rest are discarded before a single
address balance is computed. That linking is `ChainLinker.link(BlockFileStore)`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/ChainLinker.java`):

1. `readHeaders()` parses every stored block's header with `BlockParser.header(ByteBuffer)` and
   records its hash, its previous-block hash, its file location and its timestamp — nothing else is
   kept in memory per block.
2. `resolveParents()` builds a `BlockHashIndex` over all the hashes and looks up each block's parent
   by its previous-hash field.
3. `assignHeights()` starts from every block whose parent could not be found (the genesis block,
   normally exactly one) and walks the parent-to-child graph depth-first — `pending` is popped with
   `pending[--top]`, so it works as a stack — assigning each block a height one greater than its
   parent's. Nothing about the result depends on that choice: a breadth-first walk would assign the
   same heights, since a block's height is fixed by its parent's height alone.
4. `buildChain(int[])` finds the block with the greatest height — the active tip — and walks
   backwards through `parents` from there to the genesis block. Only the blocks on that walk become
   the `Chain`; everything else is the count reported as "stale blocks" in the build report.

The pipeline that hangs off this is `SnapshotBuilder.build(Path, Path)`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotBuilder.java`):
`BlockFileStore.open` locates the block files, `ChainLinker.link` produces the `Chain`,
`LedgerReplay.replay` walks it into a `Ledger`, `SnapshotBuilder.verify(Ledger)` cross-checks the
result before anything is written, and `SnapshotWriter.write` lays the ledger out on disk.

## The block file format

`BlockParser` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/BlockParser.java`)
reads the same on-disk block format the legacy daemon wrote. Two details are load-bearing for the
rest of the pipeline:

- **The genesis block cannot be hashed the way every later block is.** It was mined with the Quark
  algorithm rather than double SHA-256, so its hash is pinned as the constant `GENESIS_HASH`,
  stored in internal (reversed) byte order, and `BlockParser.header` special-cases a block whose
  previous-hash field is all zero to return that constant instead of hashing its header.
- **The header grew by 32 bytes at protocol version 4.** `headerSize(int)` returns 80 bytes below
  that version and 112 bytes from it on, to make room for a zerocoin accumulator checkpoint;
  `header(ByteBuffer)` uses that to skip past the header regardless of which shape it has.

`BlockParser.transactions(ByteBuffer)` parses the rest of the block. Each transaction input is
classified by `inputType`: a zerocoin spend is recognised by its signature script (it carries the
same null previous-output marker a coinbase does, so that check has to run first), a coinbase input
has a null previous output that is not a zerocoin spend, and everything else is `STANDARD`. Each
output is classified by `outputType`: a script that resolves to an address is `ADDRESS`, an empty
script is `EMPTY`, and a non-empty script that fails to resolve is either a zerocoin mint or
`UNSPENDABLE`, decided by `ScriptAddressResolver.isZerocoinMint`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/ScriptAddressResolver.java`).
`LedgerReplay` only ever credits or debits `ADDRESS` outputs and `STANDARD` inputs; zerocoin mints
add straight to a separate running total instead of an address balance.

## Snapshot layout

`SnapshotFormat` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotFormat.java`)
defines the on-disk layout, big-endian throughout. It is laid out as five sections, in this order,
followed by an optional signature:

| Section | Record size | Contents |
| --- | --- | --- |
| Header | 128 bytes | Magic `UGDSNAP1`, format version, tip hash and height, address/entry/transaction counts, section offsets, build timestamp (the tip block's timestamp, not when the conversion ran), total unspent, zerocoin minted |
| Address table | 40 bytes per address | Sorted by address hash so a lookup is a binary search; balance, first entry index and entry count per address |
| Block time table | 4 bytes per height | One entry per block on the chain, so a ledger entry only has to carry a height to have a date |
| Entry table | 20 bytes per entry | Amount, height, transaction index and kind, grouped per address and ordered by height within a group |
| Transaction id table | 32 bytes per transaction | Referenced by index from the entry table rather than repeating a 32-byte hash per entry |
| Signature (optional) | 12-byte header plus the signature | Magic `UGDSIGN1`, a length prefix, then the DER-encoded signature bytes; appended after everything above |

`SnapshotWriter` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotWriter.java`)
orders the two tables differently. The address table is ordered by a genuine comparison sort —
`sortedAddresses()` boxes the address indices and hands them to `Arrays.sort` with a comparator over
each address's hash. The entry table, by contrast, is built with a counting sort: `entryStarts()`
counts how many entries each address owns and prefix-sums those counts into per-address slot ranges,
then `scatterEntries()` places each ledger entry directly into its slot with no comparison at all.
Entry order within an address is preserved by the scatter, so each address's history comes out
sorted by height for free, riding on the fact that entries already arrive in chain order.
`SnapshotReader`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotReader.java`) reads all of
this back through memory-mapped `ByteBuffer`s rather than loading the file into the heap: a balance
lookup is a binary search over the address table, and a history lookup is one sequential read over
the entry range the matching address record names. Amounts are stored as `long` satoshis and
converted with `Coin.toDecimal` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/Coin.java`,
8 decimal places); each entry is tagged `RECEIVED`, `SENT`, `MINED` or `STAKED`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/EntryKind.java`).

`SnapshotReader.open` rejects a file whose magic does not match or whose version differs from the
build's own `SnapshotFormat.VERSION`, with a message telling the caller to re-import rather than
guess at a compatibility shim.

## Signing and verification

A snapshot this size cannot be handed to the signing code as one array, so it is signed by proxy.
`SnapshotDigest` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotDigest.java`)
streams a SHA-512 digest over exactly the header-declared content length — never the signature
trailer, since that would make the digest depend on whether the file is already signed — and that
digest is what actually gets signed.

`SnapshotSignature` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SnapshotSignature.java`)
wraps that digest as a `Signable` and appends the signature strictly after the snapshot's content, so
signing never disturbs a byte a reader depends on: an unsigned local build and the signed release
asset stay byte-identical over everything they share, which is what lets anyone rebuild the snapshot
themselves and compare it to the published one instead of having to trust whoever produced it.
`SnapshotSignature.read(Path)` and `getStatus()` reduce a file to exactly three outcomes, backed by
`SignatureStatus` (`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/SignatureStatus.java`):

- **`UNSIGNED`** — no trailing bytes beyond the declared content.
- **`INVALID`** — trailing bytes are present but either do not parse as a signature block (wrong
  magic, or a length outside `1..MAXIMUM_SIGNATURE_SIZE`) or fail to verify against any of
  `NetworkKey.getPublicKeys()`.
- **`SIGNED`** — the trailing bytes parse and the signature verifies against one of the trusted
  public keys.

`SnapshotReader.open` refuses to open a snapshot whose status is `INVALID` outright, but opens one
that is merely `UNSIGNED` without complaint — local queries against a freshly imported, unsigned
snapshot work normally. `bootstrap fetch` is stricter: `SnapshotDownload.install` only accepts
`SIGNED` (see below). The appended block is a 12-byte header plus a DER-encoded ECDSA signature over
the P-521 curve, the same `Signature`/`NetworkKey` pair described in full in
[Grid sporks](sporks.md); DER encodes each integer at its natural length, so the signature is not a
fixed size — a real run produced a 150-byte block on one signing and a 151-byte block on another —
bounded by `SnapshotFormat.MAXIMUM_SIGNATURE_SIZE`.

## Commands

All six commands hang off `hedgehog bootstrap`
(`application/src/main/java/org/unigrid/hedgehog/command/Bootstrap.java`), which mixes in
`NetOptions` (so `--network-keys` can override which foundation keys `sign`, `info` and `fetch`
trust) and `SnapshotOptions`.

| Command | Class | Purpose |
| --- | --- | --- |
| `bootstrap import` | `BootstrapImport` | Reads the legacy `blk*.dat` files and writes a snapshot |
| `bootstrap info` | `BootstrapInfo` | Prints the header fields of a snapshot, signature status included |
| `bootstrap balance` | `BootstrapBalance` | Prints one address's balance and transaction count |
| `bootstrap history` | `BootstrapHistory` | Lists an address's transactions, plain text or JSON |
| `bootstrap sign` | `BootstrapSign` | Appends a foundation signature to a snapshot |
| `bootstrap fetch` | `BootstrapFetch` | Downloads, verifies and installs a published snapshot |

Options, as declared on each command:

| Option | Command | Required | Meaning |
| --- | --- | --- | --- |
| `-b`, `--blocks` | `import` | yes | Directory holding the legacy `blk*.dat` files |
| `-o`, `--output` | `import` | no | Snapshot file to write; defaults to `SnapshotOptions.defaultSnapshot()` |
| `--force` | `import`, `sign`, `fetch` | no | Overwrite an existing snapshot, or replace an existing signature |
| `<address>` | `balance`, `history` | positional | Legacy Unigrid address |
| `-n`, `--limit` | `history` | no | Transactions to show, default 100 |
| `--offset` | `history` | no | Transactions to skip first, default 0 |
| `--json` | `history` | no | Print transactions as JSON instead of aligned text |
| `-k`, `--key` | `sign` | yes | Hex private key to sign with; rejected unless `NetworkKey.isTrusted` accepts it |
| `--url` | `fetch` | no | Source URL, defaults to the `bootstrap.dat.gz` of the running version's release, or of the latest release for a snapshot build |
| `-s`, `--snapshot` | inherited on every `bootstrap` subcommand | no | Snapshot path, defaults to `bootstrap.dat` in the per-platform user data directory (see [Architecture overview](architecture.md) for what that resolves to on each platform) |

`bootstrap sign` truncates the file back to its declared content length before appending a new
signature, so `--force` cleanly replaces rather than stacks a second signature block after the
first. `bootstrap fetch` downloads to a `.part` file beside the target, verifies it, and only then
moves it into place with an atomic rename — a download that fails verification never touches an
existing snapshot, and `SnapshotDownload` transparently decompresses a source whose path ends in
`.gz`. A daemon that starts without a snapshot runs the same download from the same default URL in
the background through `SnapshotInstaller`, reporting its progress on `GET /status`.

## REST API

`BootstrapResource`
(`application/src/main/java/org/unigrid/hedgehog/server/rest/BootstrapResource.java`) serves the
same snapshot over HTTP, reusing the daemon's cached `BootstrapSnapshot` rather than opening the
file per request. Every endpoint answers `503` while no snapshot is available and `400` for an
address that fails to decode.

| Endpoint | Query parameters | Returns |
| --- | --- | --- |
| `GET /bootstrap` | none | `SnapshotInfo`: the header fields, the same as `bootstrap info` |
| `GET /bootstrap/address/{address}` | none | `AddressBalance` with pending mints added, or `404` if the address is neither in the snapshot nor owed a pending mint |
| `GET /bootstrap/address/{address}/transactions` | `offset` (default 0), `limit` (default 100, capped at 1000) | A JSON array of `AddressTransaction` |

The balance also covers funds the [`MintStorage` spork](sporks.md#mintstorage) promises an address
but the chain has not minted yet. `PendingMints.amountFor`
(`application/src/main/java/org/unigrid/hedgehog/model/bootstrap/PendingMints.java`) adds every mint
whose address decodes to the same hash160 and whose height lies above the current height; a mint at
or below it is already part of the chain's balances and is left out. Mints whose address does not
decode are skipped, and the sum is kept at 8 decimals like every other amount. The current height
comes from the `ChainHeight` bean (`application/src/main/java/org/unigrid/hedgehog/model/ChainHeight.java`)
and is served by `GET /height`; until hedgehog follows the chain that mints, the snapshot's tip height
stands in for it. An address that
only has pending mints answers `200` with a `transactionCount` of 0, since mints are not ledger
entries. The `bootstrap balance` command reads the snapshot file directly and has no spork database,
so it prints the snapshot balance alone.

## The release procedure

Producing and publishing a release snapshot is a fixed sequence, run by whoever holds the legacy
data directory:

1. **Import.** `hedgehog bootstrap import -b <legacy-data-dir>/blocks -o bootstrap.dat` converts the
   block files into a snapshot.
2. **Check the tip figures against the previous release.** `hedgehog bootstrap info -s bootstrap.dat`
   on the new file and on the previous release's asset should show the same or a strictly greater tip
   height, and the balances should not have moved for addresses nobody expects to have transacted.
   This is the only check that would catch a corrupted or truncated legacy data directory before it
   is published.
3. **Sign.** `hedgehog bootstrap sign -s bootstrap.dat -k <board-member-private-key>` appends the
   signature. Any one of the four board members' keys is accepted.
4. **Publish.** `./release.sh publish --bootstrap bootstrap.dat --codename "<Name>"` compresses the
   file with `gzip -9`, checks that the jar of the release being published reports it `SIGNED`,
   signs it and every executable with the release key, attaches everything to the drafted release
   and publishes it. `gzip` was chosen over a tighter codec such as `xz` because it needs no
   dependency beyond what the JDK and the standard toolchain already provide; measured on the
   current snapshot, `gzip -9` brings the 556,361,944-byte file to roughly 314 MB against roughly
   270 MB for `xz`. `bootstrap fetch`'s default URL expects `bootstrap.dat.gz` on the running version's
   own GitHub release, which is why a release is never published before the snapshot is attached, and why
   `publish` carries the previous release's snapshot forward when no new one is given.

## Known rough edges

- **The release asset cannot be built in CI.** Producing it needs the full legacy data directory —
  4.1 GB, and not something any workflow in this repository has a copy of — plus minutes of runtime
  and several gigabytes of heap for the chain link and ledger replay. It is necessarily a manual
  procedure run by whoever holds that data, not something the release workflow can reproduce;
  `release.sh publish` therefore takes the signed file as input rather than producing it.
- **The default fetch URL is provisional.** `BootstrapFetch.defaultUrl` points at a GitHub release
  asset path, but where the snapshot will actually be hosted long-term has not been settled; the
  comment on the constant already flags that the signature, not the host, is what makes the file
  trustworthy, but the URL itself is expected to change.
- **`Signature` regenerates a full keypair on every verification.** The two-argument constructor
  that `SnapshotSignature.sign` and `isValidSignature` both use calls the no-argument constructor
  first, which runs a full P-521 keypair rejection-sampling loop before the supplied key is even
  looked at — a throwaway keypair is generated and discarded on every single check. This is the same
  cost documented for spork verification in [Grid sporks](sporks.md); it is not specific to
  snapshots, but it is paid once per `NetworkKey.getPublicKeys()` entry every time `bootstrap info`
  or `bootstrap fetch` checks a signature.
- **An `UNSIGNED` snapshot opens without complaint.** `SnapshotReader.open` only refuses `INVALID`;
  a freshly imported, never-signed file is readable by `info`, `balance` and `history` exactly as if
  it were signed. Only `bootstrap fetch` insists on `SIGNED`. That is convenient for local work but
  means nothing about `bootstrap info`'s output tells a reader whether the file it just inspected
  is the trusted release asset or a local rebuild.
- **`SnapshotDownload` decides whether to decompress by string-matching the URL path.** A source
  that serves gzip-compressed bytes from a URL not ending in `.gz` — a redirect through a CDN that
  drops the extension, for instance — would be installed as if it were the raw snapshot and fail
  `SnapshotReader`'s magic check instead of decompressing.
- **`--network-keys` is inherited by every `bootstrap` subcommand, `fetch` included.** `NetOptions`
  is mixed into the whole command tree the same way it is everywhere else in the project, so a
  caller of `bootstrap fetch` can pass `--network-keys` and replace the trust root that `fetch`
  exists to enforce. Consistent with the rest of the project, but worth calling out here: it is the
  one command whose entire purpose is checking a snapshot against the foundation's keys.
