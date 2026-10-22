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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.commons.lang3.tuple.Pair;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class PublishPeersIntegrityTest extends BaseCodecTest<PublishPeers> {
	private static final int PORT = 52883;

	@Mocked
	private Connection emptyConnection;

	@Provide
	public Arbitrary<PublishPeers> providePublishPeers(@ForAll @Positive byte nodes,
		@ForAll @IntRange(min = 4097, max = 65535) int port) throws UnknownHostException {

		final PublishPeers pp = PublishPeers.builder().build();

		for (int i = 0; i < nodes; i++) {
			final String ip = ArbitraryGenerator.ip4();
			final InetSocketAddress socketAddress = new InetSocketAddress(ip, port);
			final Node node = Node.builder().address(socketAddress).build();
			pp.getNodes().add(node);
		}

		return Arbitraries.of(pp);
	}

	@Provide
	public Arbitrary<PublishPeers> provideWithConnection(@ForAll("providePublishPeers") PublishPeers publishPeers) {
		for (Node n : publishPeers.getNodes()) {
			n.setConnection(Optional.of(emptyConnection));
		}

		return Arbitraries.of(publishPeers);
	}

	private static ByteBuf peers(int count) {
		return Unpooled.buffer().writeShort(count).writeZero(6 /* 48 bytes */);
	}

	private static ByteBuf peers(String... hosts) {
		final ByteBuf in = peers(hosts.length);

		for (String host : hosts) {
			in.writeShort(PORT);
			in.writeZero(6 /* 48 bytes */);
			ByteBufUtils.writeNullTerminatedString(host, in);
		}

		return in;
	}

	@SneakyThrows
	private static PublishPeers decoded(ByteBuf in) {
		final PublishPeers publishPeers = new PublishPeersDecoder().typedDecode(null, in).orElseThrow();

		assertThat("The decoder leaves bytes of the frame", in.isReadable(), is(false));
		return publishPeers;
	}

	private static Node node(String address) {
		return Node.builder().address(new InetSocketAddress(InetAddress.ofLiteral(address), PORT)).build();
	}

	/* A peer says what it likes, and a host that does not resolve is no node to keep */
	@Example
	public void shouldKeepNoNodeThatAPeerNamesByAHostThatDoesNotResolve() {
		assertThat(decoded(peers("no-such-host.invalid")).getNodes(), empty());
	}

	/* Looking up a name a peer sends would hold up the streams of the node for as long as a name server likes, so
	   even a name that resolves at once is dropped */
	@Example
	public void shouldDropAHostNameThatResolves() {
		assertThat(decoded(peers("localhost", "198.51.100.7")).getNodes(), contains(node("198.51.100.7")));
	}

	@Example
	public void shouldTakeTheAddressesOfBothFamilies() {
		final PublishPeers publishPeers = decoded(peers("198.51.100.7", "2001:db8:0:0:0:0:0:7", "2001:db8::8"));

		assertThat(publishPeers.getNodes(), containsInAnyOrder(node("198.51.100.7"), node("2001:db8::7"),
			node("2001:db8::8")));
		assertThat(publishPeers.getNodes().stream().noneMatch(n -> n.getAddress().isUnresolved()), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldMatchANodeOfEachFamily() {
		final PublishPeers publishPeers = PublishPeers.builder().build();

		publishPeers.getNodes().add(node("198.51.100.7"));
		publishPeers.getNodes().add(node("2001:db8::7"));

		final ByteBuf out = new PublishPeersEncoder().encode(null, publishPeers).orElseThrow();

		assertThat(decoded(out), equalTo(publishPeers));
	}

	@Property(tries = 10)
	public void shouldTakeNoMoreThanTheCapOfAListAndReadPastTheRest(
		@ForAll @IntRange(min = PublishPeersDecoder.MAX_PEERS + 1, max = Short.MAX_VALUE) int count) {

		final String[] hosts = IntStream.range(0, count)
			.mapToObj(n -> "10." + (n >> 16) + "." + (n >> 8 & 0xff) + "." + (n & 0xff)).toArray(String[]::new);

		assertThat(decoded(peers(hosts)).getNodes(), hasSize(PublishPeersDecoder.MAX_PEERS));
	}

	@Property(tries = 20)
	public void shouldRefuseAHostLongerThanAnAddressIsWritten(
		@ForAll @IntRange(min = PublishPeersDecoder.MAX_HOST_ADDRESS + 1, max = 4096) int length) {

		final ByteBuf in = peers("1".repeat(length));

		assertThrows(CorruptedFrameException.class, () -> new PublishPeersDecoder().typedDecode(null, in));
	}

	@Example
	public void shouldRefuseAHostThatDoesNotEnd() {
		final ByteBuf in = peers(1).writeShort(PORT).writeZero(6 /* 48 bytes */).writeBytes("198.51.100.7".getBytes());

		assertThrows(CorruptedFrameException.class, () -> new PublishPeersDecoder().typedDecode(null, in));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveANodeThatDoesNotResolveOutOfWhatItPublishes() {
		final PublishPeers publishPeers = PublishPeers.builder().build();
		final Node resolved = Node.builder().address(new InetSocketAddress("127.0.0.1", 52883)).build();
		final Node unresolved = Node.builder()
			.address(InetSocketAddress.createUnresolved("no-such-host.invalid", 52883)).build();

		publishPeers.getNodes().add(unresolved);
		publishPeers.getNodes().add(resolved);

		final ByteBuf out = new PublishPeersEncoder().encode(null, publishPeers).orElseThrow();

		assertThat(new PublishPeersDecoder().typedDecode(null, out).orElseThrow().getNodes(), contains(resolved));
	}

	@Property
	@SneakyThrows
	public void shouldMatch(@ForAll("providePublishPeers") PublishPeers publishPeers,
		@Mocked ChannelHandlerContext context) {

		final Optional<Pair<MutableInt, MutableInt>> sizes = getSizeHolder();

		final PublishPeers resultingPublishPeers = encodeDecode(publishPeers,
			new PublishPeersEncoder(), new PublishPeersDecoder(), context, sizes
		);

		assertThat(resultingPublishPeers, sameBeanAs(publishPeers));
		assertThat(resultingPublishPeers, equalTo(publishPeers));
		assertThat(sizes.get().getLeft(), equalTo(sizes.get().getRight()));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldNotIncludeConnectionOrPing(@ForAll("providePublishPeers") PublishPeers publishPeers,
		@Mocked ChannelHandlerContext context) {

		for (Node n : publishPeers.getNodes()) {
			n.setConnection(Optional.of(emptyConnection));
		}

		final PublishPeers resultingPublishPeers = encodeDecode(publishPeers,
			new PublishPeersEncoder(), new PublishPeersDecoder(), context
		);

		for (Node n : resultingPublishPeers.getNodes()) {
			assertThat(n.getConnection(), equalTo(Optional.empty()));
		}
	}
}
