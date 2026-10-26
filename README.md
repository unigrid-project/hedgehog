# The Sharded Unigrid Treechain Network
[![Hedgehog build status](https://github.com/unigrid-project/hedgehog/actions/workflows/build.yml/badge.svg)](https://github.com/unigrid-project/hedgehog/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/unigrid-project/hedgehog)](https://github.com/unigrid-project/hedgehog/releases/latest)
[![Test coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Funigrid-project%2Fhedgehog%2Fbadges%2Fcoverage.json)](https://github.com/unigrid-project/hedgehog/actions/workflows/build.yml)
[![Lines of code](https://img.shields.io/endpoint?url=https%3A%2F%2Fghloc.vercel.app%2Fapi%2Funigrid-project%2Fhedgehog%2Fbadge)](https://github.com/unigrid-project/hedgehog)
[![Commit activity](https://img.shields.io/github/commit-activity/w/unigrid-project/hedgehog)](https://github.com/unigrid-project/hedgehog/graphs/commit-activity)
[![Last commit](https://img.shields.io/github/last-commit/unigrid-project/hedgehog)](https://github.com/unigrid-project/hedgehog/commits/master)

<img align="right" width="300px" height="auto" src="documentation/hedgehog-logo.png" alt="Hedgehog">

Hedgehog is a high-performance, concurrent peer-to-peer treechain (blockchain) network built on top of [Netty](https://netty.io/) and QUIC. One executable is the network daemon, the command-line client that controls it and a stand-alone key utility.

__What works today:__
- Peer-to-peer distribution over QUIC
- Gridnode sporks: network parameters that take effect only when two network keys have signed them
- Network storage: files encrypted, erasure coded and spread over the gridnodes, which repair lost fragments themselves
- An S3-compatible endpoint over network storage, for the AWS SDKs, rclone, restic and other S3 clients
- REST interface
- A signed snapshot of the legacy chain: every address, balance and transaction
- Built in SOCKS5 proxy through shard groups of gridnodes (see [SOCKS5 proxy](#socks5-proxy))

__In development or planned:__
- Access to network storage through [Janus](https://github.com/unigrid-project/janus-java) and virtual desktop drives
- WebAssembly support, compute and GPU workloads
- Duality consensus
- Replacing the network and consensus chain of the [legacy daemon](https://github.com/unigrid-project/daemon)

<p align="center">
  <img width="600px" src="https://raw.githubusercontent.com/unigrid-project/hedgehog/badges/loc-history.svg" alt="Lines of today's code by the month they were written, and the total">
</p>

## Documentation
The [wiki](https://github.com/unigrid-project/hedgehog/wiki) gives a short overview of each part:

- [Architecture overview](https://github.com/unigrid-project/hedgehog/wiki/Architecture-overview)
- [Peer-to-peer network protocol](https://github.com/unigrid-project/hedgehog/wiki/Peer-to-peer-network-protocol)
- [Grid sporks](https://github.com/unigrid-project/hedgehog/wiki/Grid-sporks)
- [REST interface](https://github.com/unigrid-project/hedgehog/wiki/REST-interface)
- [Network storage](https://github.com/unigrid-project/hedgehog/wiki/Network-storage) and [Erasure coding](https://github.com/unigrid-project/hedgehog/wiki/Erasure-coding)
- [Build, testing and native image](https://github.com/unigrid-project/hedgehog/wiki/Build-testing-and-native-image)

## Building and running
Hedgehog requires Java 25+ and [Maven](https://maven.apache.org/). From the Hedgehog directory:

> mvn clean install

This creates `application/target/hedgehog-<version>-jar-with-dependencies.jar`. Start it with `java -jar`, and pass `--help` to see every command and option. Depending on the options, Hedgehog acts as a network daemon, a client or a stand-alone application.

The native executable wraps a JVM and the Hedgehog jar. Build it with `mvn package` inside `native-image`, with `GRAALVM_HOME` pointing at a GraalVM 25 installation. The result is `native-image/target/hedgehog.bin`, or `hedgehog.exe` on Windows.

### S3 endpoint
The daemon serves network storage over the S3 API on port 52885, over TLS with a self-signed certificate. Clients sign their requests with an access key:

> hedgehog s3 key create

The secret is printed only once. `hedgehog s3 key list` and `hedgehog s3 key delete <access key id>` manage the rest. Any region name is accepted. Path-style addressing always works; `--s3domain s3.example.com` adds virtual-host style, as in `bucket.s3.example.com`.

Supported: buckets, objects with metadata, conditional requests and ranges, listings V1 and V2, batch delete, copy, multipart uploads, presigned URLs and browser form uploads, checksums, tagging, CORS, versioning, lifecycle rules and Object Lock. Bucket policies, ACLs and server-side encryption settings are not, since the network already encrypts every object.

Objects read through the endpoint are kept in a local memory-mapped cache (`--s3cachedir`, `--s3cachesize`). The bucket and object index lives in network storage as well; `--s3root` mounts the index of another node, such as one that was lost, from the id in that node's `s3/index/root` file in the data directory. Keep a copy of that id, and keep it as secret as an access key: it gives read access to every object. Other options are `--s3host`, `--s3port` and `--s3plain` for clients that cannot trust a self-signed certificate. The endpoint is enabled by default; `--no-s3` starts the daemon without it.

## SOCKS5 Proxy
The daemon can run a SOCKS5 proxy that sends the traffic of local applications through the network instead of straight to
its destination. It serves `CONNECT` for TCP and `UDP ASSOCIATE` for UDP, refuses `BIND`, and asks for no authentication. A
destination can be an IPv4 or IPv6 address or a host name, which the exit looks up.

The traffic of a client goes to a shard group of gridnodes. The members of a group keep chains in memory, which several
clients share, and for each session one member of the group, chosen from the session's random fingerprint, acts as the exit:
it looks up the host name, connects to the destination and sends the answers back through the chain. A session that its exit
does not answer within five seconds turns to another member, up to three in all. What a client and its exit send each other
is encrypted between the two, so the members that only pass entries on do not see its content or destination. The groups and
their chains follow from the spork, the list of active gridnodes and the time, so every node finds the same ones without
talking to the others. They are derived anew every epoch. A session on a chain of the epoch that ended runs on for ten
minutes, or for the length of an epoch if that is shorter, and is then closed at both ends; a chain of an ended epoch that no
client or session holds is dropped once `retentionSeconds` have passed.

The proxy runs between nodes of this release, which agree on the protocol `hedgehog-proxy/0.0.1` when they connect, or
on `hedgehog-ledger/0.0.1` when both run the ledger, which carries the proxy as well. A node of an earlier release, which
offers only `hedgehog/0.0.4` and `gridspork/0.0.4`, and a node of this release built without the proxy, which offers
`hedgehog/0.0.9` first, still connect and exchange the other sporks, but take no part in the proxy: they are sent no
communication spork and get no stream for chain traffic.

### Turning it on
The proxy is off until a communication spork, signed by two of the board keys, says otherwise. While there is no valid
spork the gridnodes host no chains and a SOCKS client is answered "connection not allowed by ruleset", and so is a command
that the spork's `communicationFlags` do not allow. The flags are read when a connection or an association starts, so a change
does not end what already runs. One board key proposes the spork on a running daemon, which `cli` reaches at `--resthost`
and `--restport` (localhost and 52884 unless given), and a second board key co-signs it, as it does every spork. The values
shown are the defaults, and a field left out takes its default:

> `java -jar hedgehog-<version>-SNAPSHOT-jar-with-dependencies.jar cli gridspork-set communication --key-file <board key file> --first-version -D '{"groupSize":8,"reorganizePeriodSeconds":3600,"chainsPerGroup":4,"clientsPerChain":16,"chainCapacityBytes":33554432,"retentionSeconds":30,"maxEntryBytes":16384,"sessionIdleSeconds":300,"communicationFlags":3}'`

`--first-version` is only for the first spork of the type, as the network holds none of it. `cli gridspork-pending` lists the
proposal and `cli gridspork-cosign <digest> --key-file <other board key file>` co-signs it, after which the node passes it on.
`cli gridspork-get communication` prints the spork in force. A spork with a value outside the bounds below is refused, by the
daemon it is set on and by every node it reaches.

| Field | Meaning | Valid values |
|---|---|---|
| `groupSize` | Gridnodes per shard group. The active gridnodes are dealt evenly into as many groups as they fill at this size, and into one group when there are fewer. | 1 to 65535 |
| `reorganizePeriodSeconds` | Length of an epoch. | 60 to 4294967295 |
| `chainsPerGroup` | Chains each group hosts. A client is placed on the one with the fewest clients. | 1 to 65535 |
| `clientsPerChain` | Clients a chain takes. | 1 to 65535 |
| `chainCapacityBytes` | Memory a chain may hold. While a chain is full, the TCP connections on it wait and the UDP datagrams a client sends are dropped. | `maxEntryBytes` to 4294967295 |
| `retentionSeconds` | How long entries stay on a chain, and a chain of an ended epoch that no client or session holds. | 1 to 65535 |
| `maxEntryBytes` | Largest entry. A UDP datagram travels in one entry, so this also bounds a datagram. | 1024 to 1048576 |
| `sessionIdleSeconds` | A session, TCP or UDP, without traffic for this long is closed. | 1 to 65535 |
| `communicationFlags` | Allowed commands as bits: 1 is `CONNECT`, 2 is `UDP ASSOCIATE`. | 0 to 3 |

A daemon serves the proxy when it is started with a port for it. `--socks-port` is that port, and its default, 0, means no
proxy. `--socks-host` is the host name or address the proxy binds to, `127.0.0.1` by default. The proxy asks for no
credentials, so on an address that is not a loopback address it is open to everyone who can reach it, and the daemon logs a
warning when it binds to one. It holds 512 connections at once and closes any beyond that, and a client has ten seconds for
its greeting and command.

> `java -jar hedgehog-<version>-SNAPSHOT-jar-with-dependencies.jar daemon --socks-port 1080`

A gridnode (`-G` with its gridnode key) that the network lists as active takes part in the groups and can be the exit of a
session; any other node only joins a group as a client. A gridnode does not use the group it is a member of, so a network with
a single group cannot be used from its own gridnodes.

Any SOCKS5 client that can do without authentication works. Ask for the host name to be resolved by the proxy, so that the
name goes to the exit and does not leak to the local resolver:

> `curl --socks5-hostname 127.0.0.1:1080 https://example.org`

UDP needs a client that implements `UDP ASSOCIATE`. The proxy answers with the address of a datagram socket of its own, one
for each association, on the address the client reached the proxy at. That socket takes datagrams only from the IP address of
the client's control connection, and from the port the client named in its request or, when it named port 0, from the port
of the first valid datagram it sends. It drops fragments and anything that is not a whole SOCKS5 UDP request. The exit sends
the datagrams of an association from one socket of its own and passes on only the replies that come from an address and port
the association sent to. The association ends with the TCP connection that asked for it, or when its session ends, as it does
after `sessionIdleSeconds` without traffic.

### What to know before relying on it
- The exit sees the plaintext of what it relays, as the end of any proxy does. Use TLS between the application and its
  destination, which also keeps a hostile exit from reading or changing it.
- Gridnodes are not authenticated. A gridnode id is a key string that anyone can claim, so any other writer on the chain that
  sees the OPEN, a group member or another client attached to it, can answer first in the name of the exit and become the exit
  of that session. The keys of a session are derived from the whole handshake, so relayed or replayed entries cannot be
  spliced into another session, but nothing detects such an impostor.
- It is a proxy for the applications that are told to use it. There is no virtual network device and nothing is captured
  system-wide, so it is not a VPN.
- It does not make you anonymous. There is a single hop: traffic does not pass from one shard group to another before it
  leaves. The members a client is attached to see its network address, and when and how much it sends, but not what. The
  reply to `CONNECT` carries the address the exit connects from, so a client learns where its exit is. Anyone who watches both
  ends of a session can match them by timing and size.
- The exit refuses loopback, unspecified, private (RFC 1918 and fc00::/7), shared (100.64.0.0/10), link-local, site-local,
  multicast, reserved, benchmarking, documentation, IETF protocol (192.0.0.0/24) and local-use NAT64 addresses, and IPv6
  addresses that carry a refused IPv4 address (mapped, translated, compatible, NAT64, 6to4, Teredo and ISATAP), for TCP and
  UDP alike. It refuses `localhost` and every name below it without a lookup, and a host name that ends in a number, such as
  `127.1` or `2130706433`, or holds a colon, a bracket or a percent sign is refused before anything looks it up, as resolvers
  read it as an address. Any other name is looked up once, refused when any of its addresses is refused, and reached only at
  the addresses that were checked; a UDP destination is looked up and checked again once its association has sent nothing to
  it for a minute. These address ranges are all that protects the exit's own host and the ports of the other gridnodes: there
  is no rule for ports, so a service that listens on the exit's own public address can be reached through it.
- What a node takes on is bounded. The exit of a gridnode relays at most 256 sessions at once, at most 128 of them on one
  chain and 64 for one writer, and leaves an OPEN beyond that unanswered, so the client asks another member. It closes a
  session whose client sends nothing within 30 seconds of the exit's answer, and one whose destination falls behind by more
  than 64 full entries of what the client sends. The local proxy drops a client that falls more than 1 MiB behind what its destination sends, and
  fails a connection or an association that no exit has answered within two minutes.
- UDP datagrams that cannot be passed on are dropped, as UDP allows: one that does not fit one entry, one the client sends
  while the chain is full, one to port 0, one to a destination beyond the 64 an association keeps at a time, one to a host
  name while four others are being looked up or eight datagrams already wait for that lookup, and one for the client while 64
  already wait for the proxy's event loop, before the client's source is known, or that is longer than 65 507 bytes with
  its header.
- The proxy's UDP socket cannot tell the client from another program that sends from the client's IP address, if the client
  names no port and the other program sends first. Keep the proxy on loopback on a machine you trust, or have the client name
  its port in the request.
- A TCP connection has no half-close: a client that shuts down its sending side closes the whole connection and with it the
  session, so a protocol that sends a request, shuts down its output and then waits for the answer gets none.
- A chain takes at most 4096 writers in an epoch, a writer being a client attached to it or the feed of an exit, and never
  gives one back. Whoever can write to a chain can use them up, and until the epoch turns no new client can attach to it and
  no exit can start a feed on it.

## Releases
Every release on the [releases page](https://github.com/unigrid-project/hedgehog/releases) carries the executables for Linux, macOS on Apple Silicon and Windows, the runnable jar, and the signed chain snapshot `bootstrap.dat.gz` that `hedgehog bootstrap fetch` downloads. Intel Macs run the jar.

Every asset has a detached signature (`.asc`) made with the Unigrid Foundation release key, [release-key.asc](release-key.asc), fingerprint

> A1CB 0037 B3B9 2D59 5FA1 536C 95A9 8E88 8B0B A5D9

To verify all downloads at once, from the directory that holds them:

> gpg --import release-key.asc
>
> gpg --verify SHA256SUMS.asc SHA256SUMS
>
> sha256sum -c SHA256SUMS

### Cutting a release
Releases are cut from a clean `master` with `release.sh`, which needs `gh` logged in and the secret half of the release key:

1. `./release.sh cut` builds and tests the project, tags `vX.Y.Z`, opens the next snapshot version and pushes. The tag builds the executables and drafts the release.
2. Prepare the snapshot with `bootstrap import` and `bootstrap sign`, as described in [Legacy chain snapshot](https://github.com/unigrid-project/hedgehog/wiki/Legacy-chain-snapshot).
3. `./release.sh publish --bootstrap bootstrap.dat --codename "<Name>"` signs every asset, attaches the snapshot and a signed `SHA256SUMS`, and publishes. Without `--bootstrap` the previous release's snapshot is carried forward.

`./release.sh --help` lists the remaining options.
