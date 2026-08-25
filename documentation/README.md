# Hedgehog implementation documentation

This set documents the Hedgehog codebase as it actually stands: the shape of the process, the QUIC
peer protocol and its wire formats, the signed spork records, the REST surface, the CDI object graph
and the build and test toolchain. It deliberately covers implementation rather than motivation — the
project's pitch and the instructions for building and running a release live in the
[repository README](../README.md), and the reasoning behind the network and consensus design lives in
the white-paper submodule under `white-paper/`. Every document cites the source it describes by
repository-relative path.

| Document | Covers | Written for |
| --- | --- | --- |
| [Architecture overview](architecture.md) | The three Maven modules, the module descriptor, the picocli command tree with every option, the two servers, the daemon lifecycle, the package map and the on-disk state | Anyone new to the code — read this one first |
| [Peer-to-peer network protocol](network-protocol.md) | QUIC transport, pipeline assembly, the frame format, every packet layout, the inbound handlers, topology bookkeeping and the scheduled traffic | Work on peers, packets, codecs or connection state |
| [Grid sporks](sporks.md) | The signed parameter records: type hierarchy, what bytes are signed and which keys are trusted, persistence, mutation, propagation and the wire encoding | Changing network parameters, signing or the spork database |
| [REST interface](rest-api.md) | The Jersey listener and its providers, the full endpoint reference, the S3-compatible object store, error handling and the CLI client | API consumers, CLI work and anyone adding a resource |
| [CDI container and component lifecycle](cdi-and-lifecycle.md) | Weld bootstrap, bean discovery, `@Eager`, the `@Protected`/`@Lock` interceptor, producers, the Jersey bridge, startup and shutdown ordering, and the test-side container | Adding a bean, an injection point or a CDI-backed test |
| [Build, testing and native image](build-and-native-image.md) | The reactor and its plugin set, everyday commands, the dependency inventory, the jqwik test infrastructure, the `native-image` module, the workflows and the license header convention | Builds, tests, releases and packaging |

## Where to start

1. [Architecture overview](architecture.md), end to end. It opens with a longer, file-by-file path
   through the source, and the other five documents assume it.
2. Then whichever layer the work touches: [Peer-to-peer network protocol](network-protocol.md),
   [Grid sporks](sporks.md) or [REST interface](rest-api.md).
3. [CDI container and component lifecycle](cdi-and-lifecycle.md) before adding or moving a bean —
   startup order and the `@Eager` rules are not evident from the source alone.
4. [Build, testing and native image](build-and-native-image.md) before the first commit, for the test
   harness and the checkstyle gate that runs at `verify`.

## Quick reference

| Fact | Value | Defined in |
| --- | ---: | --- |
| Default P2P port | 52883 | `application/src/main/java/org/unigrid/hedgehog/command/option/NetOptions.java` |
| Default REST port | 52884 | `application/src/main/java/org/unigrid/hedgehog/command/option/RestOptions.java` |
| Frame magic number | `0xBABE` | `application/src/main/java/org/unigrid/hedgehog/model/network/codec/FrameDecoder.java` |
| Spork database file | `spork.db` | `application/src/main/java/org/unigrid/hedgehog/model/spork/SporkDatabase.java` |
| Entry-point class | `org.unigrid.hedgehog.Hedgehog` | `application/src/main/java/org/unigrid/hedgehog/Hedgehog.java` |
| Fat jar | `hedgehog-<version>-jar-with-dependencies.jar` | `application/assembly.xml`, `application/pom.xml` |

The spork database is written to the platform user data directory, not the working directory. The fat
jar lands in `application/target/`, and the version already carries the `-SNAPSHOT` suffix — at
`0.0.8-SNAPSHOT` the file is `application/target/hedgehog-0.0.8-SNAPSHOT-jar-with-dependencies.jar`.

## Conventions

- Source paths are repository-relative, from the repository root, so they can be pasted straight into
  an editor or `git grep`.
- The documents describe the code as it is today, rough edges included; they are not a specification
  of intended behavior, and where the source and its comments disagree both are reported.
- Each of the six documents ends with a `Known rough edges` section collecting the gaps, surprises
  and defects found in that layer.
- Cross-references between documents use the target document's own title as the link text.

`white-paper/` is a git submodule pointing at
<https://github.com/unigrid-project/documentation-white-paper>. A fresh clone leaves it empty, and
nothing in the Maven build or the workflows initializes it, so populate it explicitly with
`git submodule update --init documentation/white-paper`. The remaining file here, `hedgehog-logo.png`,
is the image used by the repository README.
