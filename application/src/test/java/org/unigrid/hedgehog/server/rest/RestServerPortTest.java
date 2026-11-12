/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server.rest;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.option.OptionsCli;

/* The REST server of a daemon run without --preview, on the host and the port the operator named. The ports of these
   tests are found here and not through the port finder that the servers of other tests get theirs from: that finder
   opens a listener on every address the first time anything asks it and hands out no port below 1101, so a test that
   used it would hold that listener whatever the server did. */
public class RestServerPortTest {
	private static final int FIRST_UNPRIVILEGED_PORT = 1024;
	private static final int LAST_PORT_BELOW_THE_FINDER = 1099;
	private static final String LISTENING = "0A";

	private static boolean isFree(final int port) {
		try (ServerSocket tcp = new ServerSocket(port, 1, InetAddress.getLoopbackAddress());
			DatagramSocket udp = new DatagramSocket(port, InetAddress.getLoopbackAddress())) {

			return true;
		} catch (IOException ex) {
			return false;
		}
	}

	@SneakyThrows
	private static int anyFreePort() {
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			return socket.getLocalPort();
		}
	}

	private static int freePortBelowTheFinder() {
		return Stream.iterate(FIRST_UNPRIVILEGED_PORT, port -> port <= LAST_PORT_BELOW_THE_FINDER, port -> port + 1)
			.filter(RestServerPortTest::isFree).findFirst().orElseThrow();
	}

	private static RestServer serverOn(final int port) {
		OptionsCli.parse(List.of("daemon"), "--resthost", "localhost", "--restport", String.valueOf(port),
			"--resttoken", RestToken.generate());

		return new RestServer();
	}

	private static void started(final RestServer server, final Runnable whileStarted) {
		try {
			server.init();
			whileStarted.run();
		} finally {
			if (Objects.nonNull(server.getChannel())) {
				server.destroy();
			}
		}
	}

	/* The ports this process listens on for every address, read from the table of the kernel: Java has no way to ask
	   which sockets a process holds open. A socket is this process's when one of its descriptors names the socket's
	   inode. */
	@SneakyThrows
	private static Set<Integer> portsListenedOnForEveryAddress() {
		final Set<String> own = new TreeSet<>();
		final Set<Integer> ports = new TreeSet<>();

		try (Stream<Path> descriptors = Files.list(Path.of("/proc/self/fd"))) {
			descriptors.forEach(descriptor -> {
				try {
					own.add(Files.readSymbolicLink(descriptor).toString());
				} catch (IOException ex) {
					/* The descriptor of the directory listing itself is closed by now */
				}
			});
		}

		for (String table : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
			for (String line : Files.readAllLines(Path.of(table)).stream().skip(1).toList()) {
				final String[] columns = line.trim().split("\\s+");
				final String[] local = columns[1].split(":");

				final boolean isOwn = own.contains("socket:[" + columns[9] + "]");

				if (columns[3].equals(LISTENING) && local[0].matches("0+") && isOwn) {
					ports.add(Integer.parseInt(local[1], 16));
				}
			}
		}

		return ports;
	}

	/* The server binds the one port it was given, on the one host it was given. A listener on every address that is
	   held for as long as the node runs is open to the network and serves nothing. */
	@Example
	public void shouldListenOnNoPortButItsOwn() {
		final int port = anyFreePort();

		started(serverOn(port), () -> assertThat(portsListenedOnForEveryAddress(), is(empty())));
	}

	/* Netty binds a port below 1101 as it binds any other, so the operator who names one gets it */
	@Example
	public void shouldBindARestPortBelowTheFirstPortTheFinderMayHandOut() {
		final int port = freePortBelowTheFinder();
		final RestServer server = serverOn(port);

		started(server, () -> assertThat(server.getPort(), equalTo(port)));
	}

	/* A port that something else listens on is refused where the server binds it, as the port of the P2P server is */
	@Example
	public void shouldFailToBindAPortThatSomethingElseListensOn() throws IOException {
		try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
			final RestServer server = serverOn(other.getLocalPort());

			assertThrows(BindException.class, server::init);
			assertThat(server.getChannel(), is(nullValue()));
		}
	}

	/* What the system says of a port that is taken names neither the port nor the server that asked for it, and a node
	   listens on two, so the failure names the address and the options that set it */
	@Example
	public void shouldNameTheAddressAndItsOptionsWhenSomethingElseListensOnThePort() throws IOException {
		try (ServerSocket other = new ServerSocket(0, 1, InetAddress.getByName("localhost"))) {
			final RestServer server = serverOn(other.getLocalPort());

			assertThat(assertThrows(BindException.class, server::init).getMessage(), startsWith("Cannot listen on "
				+ "localhost:" + other.getLocalPort() + " for REST (--resthost, --restport): "));
		}
	}

	/* The resources answer under the host and the port that the operator named, which are the ones the server binds,
	   whatever else holds that port: a location that a resource hands out is resolved against that address. The
	   container puts the path of a request straight after it, so it ends in a slash. */
	@Example
	public void shouldAnswerUnderTheHostAndThePortItWasGiven() throws URISyntaxException {
		OptionsCli.parse(List.of("daemon"), "--resthost", "localhost", "--restport", "1080");

		assertThat(RestServer.baseUri(), equalTo(URI.create("https://localhost:1080/")));
	}

	/* The colons of an IPv6 address would end the host unless it is in brackets, which an operator may have put there */
	@Example
	public void shouldAnswerUnderAnIpv6AddressInBrackets() throws URISyntaxException {
		for (String host : List.of("::1", "[::1]")) {
			OptionsCli.parse(List.of("daemon"), "--resthost", host, "--restport", "1080");

			assertThat(RestServer.baseUri(), equalTo(URI.create("https://[::1]:1080/")));
		}
	}

	/* The resolver takes names that are no host names by the book, such as one with an underscore, and so does this */
	@Example
	public void shouldAnswerUnderANameThatOnlyTheResolverKnows() throws URISyntaxException {
		OptionsCli.parse(List.of("daemon"), "--resthost", "rest_node", "--restport", "1080");

		assertThat(RestServer.baseUri().toString(), equalTo("https://rest_node:1080/"));
	}
}
