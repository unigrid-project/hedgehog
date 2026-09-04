/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.DestinationPolicy;
import org.unigrid.hedgehog.proxy.session.Resolver;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class DatagramDestinationsTest {
	private static final int PORT = 53;
	private static final byte[] PAYLOAD = { 1, 2, 3 };
	private static final Resolver NEVER = host -> {
		throw new AssertionError("Nothing to resolve, asked for " + host);
	};

	/* The refused ranges in every IP literal spelling, one per class: IPv4 ranges, IPv4 inside IPv6 as mapped,
	   translated, compatible, NAT64, local-use NAT64, 6to4, Teredo server and client, and ISATAP under a local and a
	   global interface identifier, and the IPv6 ranges */
	private static final List<String> REFUSED_LITERALS = List.of("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1",
		"169.254.169.254", "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1", "198.51.100.1",
		"203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::", "::1", "::ffff:127.0.0.1",
		"::ffff:10.0.0.1", "::ffff:0:7f00:1", "::127.0.0.1", "64:ff9b::7f00:1", "64:ff9b:1::808:808",
		"2002:7f00:1::1", "2001:0:7f00:1::1", "2001:0:4136:e378:8000:63bf:80ff:fffe", "2600::5efe:7f00:1",
		"2600::200:5efe:a00:1", "2001:db8::1", "fc00::1", "fd12::1", "fe80::1", "fec0::1", "ff02::1");

	/* Names the destination policy refuses as local */
	private static final List<String> REFUSED_NAMES = List.of("localhost", "localhost.", "LocalHost", "db.localhost");

	/* Spellings a SocksAddress refuses to build, so no datagram ever reaches the policy with them */
	private static final List<String> SPELLED_ADDRESS_NAMES = List.of("2130706433", "0177.0.0.1", "0x7f.1", "127.1",
		"::1", "fe80::1%eth0");

	private record Sent(InetAddress address, int port, byte[] bytes) {
		/* One call of the sink */
	}

	private final AtomicLong clock = new AtomicLong(1_000_000);
	private final List<Sent> sent = new ArrayList<>();
	private final List<Runnable> lookups = new ArrayList<>();
	private final AtomicInteger resolutions = new AtomicInteger();
	private Map<String, List<String>> answers;

	@BeforeTry
	public void start() {
		clock.set(1_000_000);
		sent.clear();
		lookups.clear();
		resolutions.set(0);
		answers = Map.of("example.org", List.of("93.184.215.14", "2606:2800:21f::1"));
	}

	private static InetAddress address(String literal) {
		try {
			return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
		} catch (UnknownHostException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static SocksAddress literal(String text) {
		return new SocksAddress(text.contains(":") ? Socks5AddressType.IPv6 : Socks5AddressType.IPv4, text, PORT);
	}

	private static SocksAddress name(String text) {
		return new SocksAddress(Socks5AddressType.DOMAIN, text, PORT);
	}

	/* A destination left behind was never sent to, so at a later reading it looks idle and is forgotten before it
	   can show; at the clock's zero it stays */
	private void startTheClockAtZero() {
		clock.set(0);
	}

	private Resolver counting() {
		return host -> {
			resolutions.incrementAndGet();
			return answers.getOrDefault(host, List.of()).stream()
				.map(DatagramDestinationsTest::address).toList();
		};
	}

	private DatagramDestinations over(DestinationPolicy policy, Resolver resolver) {
		return through(policy, resolver, Runnable::run);
	}

	private DatagramDestinations queueing(Resolver resolver) {
		return through(DestinationPolicy.publicOnly(), resolver, lookups::add);
	}

	private DatagramDestinations through(DestinationPolicy policy, Resolver resolver, Executor resolving) {
		return new DatagramDestinations(policy, resolver, resolving, clock::get,
			(address, port, bytes) -> sent.add(new Sent(address, port, bytes)));
	}

	@Example
	public void shouldSendToAnAdmittedLiteralAsGivenWithoutAskingTheResolver() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

		destinations.send(literal("93.184.215.14"), PAYLOAD);
		destinations.send(literal("2606:2800:21f::1"), PAYLOAD);

		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.14"),
			address("2606:2800:21f::1"))));
		assertThat(sent.get(0).port(), is(PORT));
		assertThat(sent.get(0).bytes(), equalTo(PAYLOAD));
		assertThat(destinations.dropped(), is(0L));
	}

	@Example
	public void shouldNeverSendToALiteralTheDestinationPolicyRefuses() {
		for (String refused : REFUSED_LITERALS) {
			final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

			destinations.send(literal(refused), PAYLOAD);
			destinations.send(literal(refused), PAYLOAD);

			assertThat(refused, sent, is(empty()));
			assertThat(refused, destinations.dropped(), is(2L));
		}
	}

	@Example
	public void shouldNeverSendToANameThatIsLocalOrSpellsAnAddress() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

		for (String refused : REFUSED_NAMES) {
			destinations.send(name(refused), PAYLOAD);
		}

		assertThat(sent, is(empty()));
		assertThat(destinations.dropped(), is((long) REFUSED_NAMES.size()));
	}

	@Example
	public void shouldRefuseANameThatSpellsAnAddressWhenItIsBuilt() {
		for (String spelling : SPELLED_ADDRESS_NAMES) {
			Expect.assertThrows(IllegalArgumentException.class, () -> name(spelling));
		}
	}

	@Example
	public void shouldHoldTheTestVariantToTheSameRulesBesidesLoopback() {
		final DatagramDestinations destinations = over(DestinationPolicy.allowLoopback(), NEVER);

		destinations.send(literal("127.0.0.1"), PAYLOAD);
		destinations.send(literal("10.1.2.3"), PAYLOAD);
		destinations.send(literal("::ffff:10.0.0.1"), PAYLOAD);

		assertThat(sent.size(), is(1));
		assertThat(sent.get(0).address(), equalTo(address("127.0.0.1")));
		assertThat(destinations.dropped(), is(2L));
	}

	@Example
	public void shouldResolveANameOnceAndSendToTheFirstCheckedAddress() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());

		destinations.send(name("example.org"), PAYLOAD);
		destinations.send(name("example.org"), new byte[] { 4 });

		assertThat(resolutions.get(), is(1));
		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.14"),
			address("93.184.215.14"))));
	}

	@Example
	public void shouldRefuseANameWhenAnyOfItsAddressesIsRefusedAndRememberIt() {
		answers = Map.of("example.org", List.of("93.184.215.14", "10.0.0.1"));

		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());

		destinations.send(name("example.org"), PAYLOAD);
		destinations.send(name("example.org"), PAYLOAD);

		assertThat(sent, is(empty()));
		assertThat(resolutions.get(), is(1));
		assertThat(destinations.dropped(), is(2L));
	}

	@Example
	public void shouldRefuseANameThatDoesNotResolveOrWhoseResolverFails() {
		final DatagramDestinations unknown = over(DestinationPolicy.publicOnly(), counting());
		final DatagramDestinations failing = over(DestinationPolicy.publicOnly(), host -> {
			throw new IllegalStateException("resolver down");
		});

		unknown.send(name("nowhere.example"), PAYLOAD);
		failing.send(name("example.org"), PAYLOAD);

		assertThat(sent, is(empty()));
		assertThat(unknown.dropped() + failing.dropped(), is(2L));
	}

	@Example
	public void shouldNotSeeAnAnswerThatChangedWhileItKeepsSendingToTheName() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());

		destinations.send(name("example.org"), PAYLOAD);
		answers = Map.of("example.org", List.of("127.0.0.1"));
		clock.addAndGet(DatagramDestinations.IDLE_MILLIS - 1);
		destinations.send(name("example.org"), PAYLOAD);

		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.14"),
			address("93.184.215.14"))));
		assertThat(resolutions.get(), is(1));
	}

	@Example
	public void shouldLookAFreshNameUpAndCheckItAgainAfterAnIdlePeriod() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());

		destinations.send(name("example.org"), PAYLOAD);
		answers = Map.of("example.org", List.of("127.0.0.1"));
		clock.addAndGet(DatagramDestinations.IDLE_MILLIS);
		destinations.send(name("example.org"), PAYLOAD);

		assertThat(sent.size(), is(1));
		assertThat(resolutions.get(), is(2));
		assertThat(destinations.dropped(), is(1L));
	}

	@Example
	public void shouldKeepADestinationForAsLongAsItIsSentToAndForgetItOnceIdle() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());
		final InetSocketAddress reply = new InetSocketAddress(address("93.184.215.14"), PORT);
		final long step = DatagramDestinations.IDLE_MILLIS * 3 / 5;

		destinations.send(name("example.org"), PAYLOAD);
		clock.addAndGet(step);
		destinations.send(name("example.org"), PAYLOAD);
		clock.addAndGet(step);
		destinations.send(name("example.org"), PAYLOAD);

		assertThat(resolutions.get(), is(1));
		assertThat(sent.size(), is(3));
		assertThat(destinations.allowsReplyFrom(reply), is(true));

		clock.addAndGet(DatagramDestinations.IDLE_MILLIS);
		assertThat(destinations.allowsReplyFrom(reply), is(false));
	}

	@Example
	public void shouldRelayAnEmptyDatagramToAnAdmittedDestination() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

		destinations.send(literal("93.184.215.14"), new byte[0]);

		assertThat(sent.size(), is(1));
		assertThat(sent.get(0).bytes().length, is(0));
		assertThat(destinations.dropped(), is(0L));
	}

	@Example
	public void shouldHoldDatagramsWhileANameIsLookedUpAndSendThemInOrder() {
		final DatagramDestinations destinations = queueing(counting());

		destinations.send(name("example.org"), new byte[] { 1 });
		destinations.send(name("example.org"), new byte[] { 2 });
		assertThat(sent, is(empty()));
		assertThat(lookups.size(), is(1));

		lookups.get(0).run();
		destinations.send(name("example.org"), new byte[] { 3 });

		assertThat(sent.stream().map(entry -> entry.bytes()[0]).toList(), equalTo(List.of((byte) 1, (byte) 2,
			(byte) 3)));
		assertThat(resolutions.get(), is(1));
	}

	@Example
	public void shouldHoldNoMoreThanTheLimitWhileANameIsLookedUp() {
		final DatagramDestinations destinations = queueing(counting());

		for (int i = 0; i < DatagramDestinations.MAX_WAITING + 2; i++) {
			destinations.send(name("example.org"), new byte[] { (byte) i });
		}

		lookups.get(0).run();

		assertThat(sent.size(), is(DatagramDestinations.MAX_WAITING));
		assertThat(destinations.dropped(), is(2L));
	}

	@Example
	public void shouldLookUpNoMoreNamesAtOnceThanTheLimitAndNeverHoldBackALiteral() {
		final DatagramDestinations destinations = queueing(counting());

		startTheClockAtZero();

		for (int i = 0; i < DatagramDestinations.MAX_LOOKUPS + 2; i++) {
			destinations.send(name("host" + i + ".example"), PAYLOAD);
		}

		assertThat(lookups.size(), is(DatagramDestinations.MAX_LOOKUPS));
		assertThat(destinations.dropped(), is(2L));

		destinations.send(literal("93.184.215.14"), PAYLOAD);
		assertThat(sent.size(), is(1));

		final SocksAddress turnedAway = name("host" + DatagramDestinations.MAX_LOOKUPS + ".example");

		destinations.send(turnedAway, PAYLOAD);
		assertThat(lookups.size(), is(DatagramDestinations.MAX_LOOKUPS));
		assertThat(destinations.dropped(), is(3L));

		lookups.get(0).run();
		destinations.send(turnedAway, PAYLOAD);
		assertThat(lookups.size(), is(DatagramDestinations.MAX_LOOKUPS + 1));

		lookups.get(1).run();
		destinations.send(name("another.example"), PAYLOAD);
		assertThat(lookups.size(), is(DatagramDestinations.MAX_LOOKUPS + 2));
	}

	@Example
	public void shouldLeaveNoTraceOfALookupTheExecutorTurnsAway() {
		final AtomicBoolean busy = new AtomicBoolean(true);
		final DatagramDestinations destinations = through(DestinationPolicy.publicOnly(), counting(), lookup -> {
			if (busy.get()) {
				throw new RejectedExecutionException("No thread to look a name up on");
			}

			lookups.add(lookup);
		});

		startTheClockAtZero();

		for (int i = 0; i <= DatagramDestinations.MAX_LOOKUPS; i++) {
			destinations.send(name("example.org"), PAYLOAD);
		}

		busy.set(false);
		destinations.send(name("example.org"), PAYLOAD);
		assertThat(destinations.dropped(), is(DatagramDestinations.MAX_LOOKUPS + 1L));
		assertThat(lookups.size(), is(1));

		for (int i = 1; i <= DatagramDestinations.MAX_LOOKUPS; i++) {
			destinations.send(name("host" + i + ".example"), PAYLOAD);
		}

		assertThat(lookups.size(), is(DatagramDestinations.MAX_LOOKUPS));
		assertThat(destinations.dropped(), is(DatagramDestinations.MAX_LOOKUPS + 2L));

		lookups.get(0).run();
		assertThat(sent.size(), is(1));
	}

	@Example
	public void shouldSendToALiteralWhileANameIsLookedUp() throws InterruptedException {
		final CountDownLatch lookingUp = new CountDownLatch(1);
		final CountDownLatch answer = new CountDownLatch(1);
		final List<Thread> threads = new ArrayList<>();
		final DatagramDestinations destinations = through(DestinationPolicy.publicOnly(), host -> {
			lookingUp.countDown();
			awaitBounded(answer);
			return List.of(address("93.184.215.14"));
		}, lookup -> {
			final Thread thread = new Thread(lookup, "lookup");

			threads.add(thread);
			thread.start();
		});

		destinations.send(name("example.org"), PAYLOAD);
		awaitBounded(lookingUp);
		destinations.send(literal("93.184.215.15"), PAYLOAD);
		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.15"))));

		answer.countDown();
		threads.get(0).join(RecordingTargetListener.TIMEOUT_MILLIS);
		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.15"),
			address("93.184.215.14"))));
	}

	private static void awaitBounded(CountDownLatch latch) {
		try {
			assertThat(latch.await(RecordingTargetListener.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), is(true));
		} catch (InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Example
	public void shouldDropWhatWaitedForALookupThatFinishedAfterTheNameWasForgotten() {
		final DatagramDestinations destinations = queueing(counting());

		destinations.send(name("example.org"), PAYLOAD);
		clock.addAndGet(DatagramDestinations.IDLE_MILLIS);
		destinations.send(literal("93.184.215.14"), PAYLOAD);
		lookups.get(0).run();

		assertThat(sent.stream().map(Sent::address).toList(), equalTo(List.of(address("93.184.215.14"))));
		assertThat(destinations.dropped(), is(1L));
	}

	@Example
	public void shouldKeepNoMoreThanTheLimitOfDestinationsUntilSomeAreForgotten() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

		for (int i = 1; i <= DatagramDestinations.MAX_DESTINATIONS + 1; i++) {
			destinations.send(literal("93.184.216." + i), PAYLOAD);
		}

		assertThat(sent.size(), is(DatagramDestinations.MAX_DESTINATIONS));
		assertThat(destinations.dropped(), is(1L));

		clock.addAndGet(DatagramDestinations.IDLE_MILLIS);
		destinations.send(literal("93.184.216.200"), PAYLOAD);
		assertThat(sent.size(), is(DatagramDestinations.MAX_DESTINATIONS + 1));
	}

	@Example
	public void shouldLeaveNoTraceOfDatagramsToPortZero() {
		final DatagramDestinations destinations = queueing(counting());

		startTheClockAtZero();

		for (int i = 0; i < DatagramDestinations.MAX_DESTINATIONS; i++) {
			destinations.send(new SocksAddress(Socks5AddressType.DOMAIN, "host" + i + ".example", 0), PAYLOAD);
		}

		destinations.send(name("example.org"), PAYLOAD);
		assertThat(lookups.size(), is(1));
		assertThat(destinations.dropped(), is((long) DatagramDestinations.MAX_DESTINATIONS));
	}

	@Example
	public void shouldDropDatagramsToPortZero() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);

		destinations.send(new SocksAddress(Socks5AddressType.IPv4, "93.184.215.14", 0), PAYLOAD);

		assertThat(sent, is(empty()));
		assertThat(destinations.dropped(), is(1L));
	}

	@Property(tries = 50)
	public void shouldTakeRepliesOnlyFromWhereItSent(@ForAll @IntRange(min = 1, max = 0xFFFF) int port,
		@ForAll @IntRange(min = 1, max = 0xFFFF) int otherPort) {

		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);
		final InetAddress sentTo = address("93.184.215.14");

		destinations.send(new SocksAddress(Socks5AddressType.IPv4, "93.184.215.14", port), PAYLOAD);

		assertThat(destinations.allowsReplyFrom(new InetSocketAddress(sentTo, port)), is(true));
		assertThat(destinations.allowsReplyFrom(new InetSocketAddress(address("93.184.215.15"), port)), is(false));
		assertThat(destinations.allowsReplyFrom(new InetSocketAddress(sentTo, otherPort)), is(port == otherPort));
	}

	@Example
	public void shouldTakeRepliesOnlyFromTheAddressAChosenNameWasSentTo() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), counting());

		destinations.send(name("example.org"), PAYLOAD);

		assertThat(destinations.allowsReplyFrom(new InetSocketAddress(address("93.184.215.14"), PORT)), is(true));
		assertThat(destinations.allowsReplyFrom(new InetSocketAddress(address("2606:2800:21f::1"), PORT)),
			is(false));
	}

	@Example
	public void shouldTakeNoRepliesFromRefusedDestinationsOrOnceTheyAreForgotten() {
		final DatagramDestinations destinations = over(DestinationPolicy.publicOnly(), NEVER);
		final InetSocketAddress refused = new InetSocketAddress(address("10.0.0.1"), PORT);
		final InetSocketAddress admitted = new InetSocketAddress(address("93.184.215.14"), PORT);

		destinations.send(literal("10.0.0.1"), PAYLOAD);
		destinations.send(literal("93.184.215.14"), PAYLOAD);
		assertThat(destinations.allowsReplyFrom(refused), is(false));
		assertThat(destinations.allowsReplyFrom(admitted), is(true));

		clock.addAndGet(DatagramDestinations.IDLE_MILLIS);
		assertThat(destinations.allowsReplyFrom(admitted), is(false));
	}
}
