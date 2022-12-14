<img src="documentation/hedgehog-dark.svg" alt="Hedgehog">

# The Sharded Unigrid Treechain Network

Hedgehog is a high-performant, concurrent peer-to-peer treechain (blockchain) network built on top of [Netty](https://netty.io/) and [Java NIO](https://docs.oracle.com/javase/8/docs/technotes/guides/io/index.html).

__Currently published features:__
- Gridnode sporks and configurable network properties
- Peer to peer distriubution
- REST interface

__Upcoming features currently in development:__
- Shard group and network storage (accesible via [Janus](https://github.com/unigrid-project/janus-java) and virtual desktop drives)
- Built in SOCKS5 Proxy for VPN-like functionality
- Amazon S3 REST API to access storage on the network

__Features slated for 2023:__
- Web Assembly support
- Compute workloads
- Duality  consensus
- GPU workloads

__Secondary goals:__
- Replacement of the network and consensus chain in the [legacy daemon](https://github.com/unigrid-project/daemon), including complete migration to Hedgehog.

## For developers that want to build Hedgehog
Hedgehog requires Java 11+. To run and build the distribution you need [Maven](https://maven.apache.org/). To execute a build you need to run the following command from within the Hedgehog directory:

> mvn clean install

This will create an archive in `target/hedgehog-<version>-SNAPSHOT-jar-with-dependencies.jar`. This can then be started with `java -jar hedgehog-<version>-SNAPSHOT-jar-with-dependencies.jar`.

## Running Hedgehog
While most people will not run Hedgehog manually, it is certainly possible. For documentation on all the features in the distribution please run the Hedgehog jar with `java -jar hedgehog-<version>-SNAPSHOT-jar-with-dependencies.jar --help`. This will display all the options available when executing the application.

Depending on the options passed, Hedgehog will act as a network daemon, client or stand-alone application.

## Native Image Support
To get the native image going after changes, you might have to run the GraalVM agent in order to get resources, predefined classes and relfection to work properly. Taking Linux as an example, this can be done with `/home/<your-user-name>/.m2/repository/org/apache/geronimo/arthur/cache/graal/22.3.0/distribution_exploded/bin/java -agentlib:native-image-agent=config-output-dir=./graal-trace/ -jar target/hedgehog-0.0.1-SNAPSHOT-jar-with-dependencies.jar daemon´. This will generate a `graal-trace` directory with everything needed. Just make sure you are standing in the hedgehog directory when you execute the command.

When everything is set up correctly, the native image can be built with ` mvn arthur:native-image`, putting it in the `target/` directory. This goal also downloads the GraalVM distribution and unarchives it into the directory of the previous step. So if you are missing GraalVM and need to run the agent, execute this step first in order to download it.
