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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
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

	/* What an address is written with, and the sign that sets the scope of one apart */
	private static final String HEX_DIGITS = "0123456789abcdefABCDEF";
	private static final String ADDRESS_CHARACTERS = HEX_DIGITS + ".:%";

	private static final MutableInt INTERFACE_LOOKUPS = new MutableInt();
	private static volatile Thread decoding;

	@Mocked
	private Connection emptyConnection;

	/* The last part of an IPv4 address fills the bytes that the parts before it leave, so one is written with one
	   part to four */
	private static Arbitrary<String> address4(int parts) {
		final Arbitrary<String> head = Arbitraries.integers().between(0, 0xff).map(part -> part + ".").list()
			.ofSize(parts - 1).map(leading -> String.join("", leading));
		final Arbitrary<String> last = Arbitraries.longs().between(0, (1L << 8 * (5 - parts)) - 1).map(String::valueOf);

		return Combinators.combine(head, last).as(String::concat);
	}

	private static Arbitrary<String> addresses4() {
		final Arbitrary<String> padded = Arbitraries.integers().between(0, 0xff).map(part -> String.format("%03d", part))
			.list().ofSize(4).map(parts -> String.join(".", parts));

		return Arbitraries.oneOf(address4(4), address4(3), address4(2), address4(1), padded);
	}

	private static Arbitrary<String> groups(int least, int most) {
		return Arbitraries.strings().withChars(HEX_DIGITS).ofMinLength(1).ofMaxLength(4).list().ofMinSize(least)
			.ofMaxSize(most).map(groups -> String.join(":", groups));
	}

	/* What follows the first group of an IPv6 address: the other seven, fewer of them around the two colons that
	   stand for the groups left out, and either with an IPv4 address in place of the last two */
	private static Arbitrary<String> afterTheFirstGroup() {
		return Arbitraries.oneOf(
			groups(7, 7).map(rest -> ":" + rest),
			Combinators.combine(groups(0, 3), groups(0, 3))
				.as((head, tail) -> (head.isEmpty() ? "" : ":" + head) + "::" + tail),
			Combinators.combine(groups(5, 5), address4(4)).as((rest, last) -> ":" + rest + ":" + last),
			Combinators.combine(groups(0, 4), address4(4))
				.as((head, last) -> (head.isEmpty() ? "" : ":" + head) + "::" + last));
	}

	private static Arbitrary<String> addresses6() {
		final Arbitrary<String> first = Arbitraries.strings().withChars(HEX_DIGITS).ofMinLength(1).ofMaxLength(4);

		return Arbitraries.oneOf(
			Combinators.combine(first, afterTheFirstGroup()).as(String::concat),
			groups(0, 7).map(tail -> "::" + tail),
			Combinators.combine(Arbitraries.of("::", "::ffff:", "::FFFF:", "0:0:0:0:0:ffff:", "::1:"), address4(4))
				.as(String::concat));
	}

	/* The number of a scope is as much as an int holds, with any zeros before it. The first group keeps the address
	   from being an IPv4 address held in an IPv6 one, which takes no scope */
	private static Arbitrary<String> addresses6OfAScope() {
		final Arbitrary<Integer> scope = Arbitraries.integers().between(0, Integer.MAX_VALUE);

		return Combinators.combine(Arbitraries.of("fe80", "FE80", "fec0", "2001"), afterTheFirstGroup(),
			Arbitraries.oneOf(scope.map(String::valueOf), scope.map(number -> String.format("%011d", number))))
			.as((first, rest, number) -> first + rest + "%" + number);
	}

	@Provide
	public Arbitrary<String> provideAddresses() {
		return Arbitraries.oneOf(addresses4(), addresses6(), addresses6OfAScope());
	}

	/* Texts of what an address is written with that are no address, or are one by chance: an address with one of its
	   characters replaced, taken out or added to, one with a scope that may not follow it, and anything at all */
	@Provide
	public Arbitrary<String> provideTextsNearAnAddress() {
		final Arbitrary<String> anything = Arbitraries.strings().withChars(ADDRESS_CHARACTERS).ofMaxLength(24);
		final Arbitrary<String> changed = Combinators.combine(provideAddresses(), Arbitraries.integers().between(0, 63),
			Arbitraries.strings().withChars(ADDRESS_CHARACTERS).ofMaxLength(2)).as((address, at, put) -> {
				final int index = at % address.length();

				return address.substring(0, index) + put + address.substring(index + 1);
			});
		final Arbitrary<String> scoped = Combinators.combine(provideAddresses(),
			Arbitraries.of("%", "%7", "%2147483648", "%99999999999")).as(String::concat);

		return Arbitraries.oneOf(anything, changed, scoped)
			.filter(text -> text.length() <= PublishPeersDecoder.MAX_HOST_ADDRESS);
	}

	/* Each is the name of an interface to the JDK: letters, a number with something before or after it, a number
	   larger than an int holds, and the seven of another script */
	@Provide
	public Arbitrary<String> provideScopesThatAreNoNumber() {
		return Arbitraries.of("nosuchif", "lo", "eth0", "x", "7x", "x7", "-7", "+7", " 7", "7 ", "7%7", "0x7",
			"2147483648", "99999999999", "00000000009999999999", Character.toString(0x667));
	}

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

	/* What the JDK makes of a text, which is all the decoder took before it looked at a text itself */
	private static Set<Node> readByTheJdk(String host) {
		try {
			return Set.of(node(host));
		} catch (IllegalArgumentException ex) {
			return Set.of();
		}
	}

	private static boolean namesAScope(String host) {
		final int percent = host.indexOf('%');

		return percent >= 0 && !host.substring(percent + 1).matches("[0-9]*");
	}

	/* The times this thread asks the operating system for an interface by its name from here on. Each is answered
	   with no interface, as the name of an interface of another host is, and not by the operating system: a fake
	   that hands a call on to the method it stands for calls itself without end once the class has a second one,
	   and each test makes its own. They count as one, since the last of them answers for all. */
	private static MutableInt interfaceLookups() {
		decoding = Thread.currentThread();

		new MockUp<NetworkInterface>() {
			@Mock public /* static */ NetworkInterface getByName(String name) {
				if (Thread.currentThread() == decoding) {
					INTERFACE_LOOKUPS.increment();
				}

				return null;
			}
		};

		INTERFACE_LOOKUPS.setValue(0);
		return INTERFACE_LOOKUPS;
	}

	/* The times this thread has the JDK read a text as an address from here on. The JDK tries the earlier family
	   first whatever the text is, and the class that does so is sealed and takes no fake, so a reading is counted
	   where that family is tried, and handed on for the tests that follow. Only one test may count: a second fake
	   of the class would hand on to the first without end. */
	private static MutableInt readingsOfAnAddress() {
		final MutableInt readings = new MutableInt();
		final Thread reading = Thread.currentThread();

		new MockUp<Inet4Address>() {
			@Mock public /* static */ Inet4Address ofLiteral(Invocation invocation, String text) {
				if (Thread.currentThread() == reading) {
					readings.increment();
				}

				return invocation.proceed();
			}
		};

		return readings;
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

	/* The name of a scope means something only on the host that wrote it, and the JDK finds out whether this host has
	   an interface of that name by asking the operating system for its interfaces. A frame of 1024 such hosts is
	   some 14 kB, so a peer must not be able to turn each of them into a system call on the thread of the stream */
	@Example
	public void shouldAskForNoInterfaceWhenAPeerNamesTheScopeOfAHost() {
		final String[] hosts = Collections.nCopies(PublishPeersDecoder.MAX_PEERS, "::1%nosuchif").toArray(String[]::new);
		final MutableInt lookups = interfaceLookups();

		assertThat(decoded(peers(hosts)).getNodes(), empty());
		assertThat("Interface lookups made while a frame of scoped hosts is decoded", lookups.intValue(), is(0));
	}

	/* A host of this node may have an interface of the name a peer wrote, so a host with such a scope is skipped
	   whatever the name is, and the hosts around it are taken as they are without it */
	@Property(tries = 100)
	public void shouldSkipAHostWhoseScopeIsNoNumberWithoutAskingForAnInterface(
		@ForAll("provideScopesThatAreNoNumber") String scope) {

		final List<String> addresses = List.of("::1", "fe80::1", "FE80:0:0:0:0:0:0:1", "2001:db8::7");
		final String[] hosts = Stream.concat(Stream.of("198.51.100.7", "2001:db8::8"),
			addresses.stream().map(address -> address + "%" + scope)).toArray(String[]::new);

		final ByteBuf in = peers(hosts);
		final MutableInt lookups = interfaceLookups();
		final PublishPeers publishPeers = decoded(in);

		assertThat("Interface lookups made for the scope " + scope, lookups.intValue(), is(0));
		assertThat(publishPeers.getNodes(), containsInAnyOrder(node("198.51.100.7"), node("2001:db8::8")));
	}

	/* The number of a scope names no interface of this host, so it stays with its address */
	@Example
	public void shouldKeepTheNumberOfAScope() {
		final ByteBuf in = peers("fe80::1%7", "fe80::2%2147483647", "fe80::3%00000000042");
		final MutableInt lookups = interfaceLookups();
		final PublishPeers publishPeers = decoded(in);

		assertThat(lookups.intValue(), is(0));
		assertThat(publishPeers.getNodes().stream().map(n -> (Inet6Address) n.getAddress().getAddress())
			.map(Inet6Address::getScopeId).toList(), containsInAnyOrder(7, Integer.MAX_VALUE, 42));
	}

	/* A name is known by what it is written with. Leaving it to the JDK to find that a name is no address costs an
	   exception for each family, both with the stack of the thread, and a frame holds 1024 names */
	@Example
	public void shouldNotHaveTheJdkReadAHostNameAsAnAddress() {
		final List<String> names = List.of("no-such-host.invalid", "localhost", "x", "198.51.100.7/24", " 198.51.100.7",
			"2001:db8::7 ");
		final String[] hosts = IntStream.range(0, PublishPeersDecoder.MAX_PEERS)
			.mapToObj(n -> names.get(n % names.size())).toArray(String[]::new);

		final ByteBuf in = peers(hosts);
		final MutableInt readings = readingsOfAnAddress();

		assertThat(decoded(in).getNodes(), empty());
		assertThat("Hosts of a frame of names that the JDK is asked to read as an address", readings.intValue(), is(0));
	}

	/* The brackets that set an address apart in a URI are no part of it, and a peer writes the address alone */
	@Example
	public void shouldSkipAnAddressInBrackets() {
		assertThat(decoded(peers("[2001:db8::7]", "198.51.100.7")).getNodes(), contains(node("198.51.100.7")));
	}

	@Property(tries = 2000)
	public void shouldTakeAnAddressOfEitherFamilyInEachFormItIsWrittenIn(@ForAll("provideAddresses") String host) {
		assertThat(decoded(peers(host)).getNodes(), contains(node(host)));
	}

	/* Looking at what a text is written with is to drop a name and the name of a scope, and nothing that the JDK
	   reads as an address without them */
	@Property(tries = 2000)
	public void shouldTakeOfTheTextsNearAnAddressWhatTheJdkReadsAsOne(@ForAll("provideTextsNearAnAddress") String host) {
		final Set<Node> read = namesAScope(host) ? Set.of() : readByTheJdk(host);

		assertThat(decoded(peers(host)).getNodes(), equalTo(read));

		/* Both kinds of text have to come up for the comparison to say anything */
		Statistics.label("read as an address").collect(!read.isEmpty()).coverage(coverage -> {
			coverage.check(true).percentage(share -> share > 10);
			coverage.check(false).percentage(share -> share > 10);
		});
	}

	/* A host that is skipped has its place in the list all the same, as a name always had, so no more of a list is
	   looked at for the hosts that are skipped */
	@Example
	public void shouldCountAHostItSkipsAsOneOfTheCap() {
		final Stream<String> addresses = IntStream.range(0, PublishPeersDecoder.MAX_PEERS)
			.mapToObj(n -> "10.0." + (n >> 8) + "." + (n & 0xff));
		final String[] hosts = Stream.concat(Stream.of("no-such-host.invalid", "fe80::1%nosuchif"), addresses)
			.toArray(String[]::new);

		assertThat(decoded(peers(hosts)).getNodes(), hasSize(PublishPeersDecoder.MAX_PEERS - 2));
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
