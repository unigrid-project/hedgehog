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

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.NoiseNk.Initiation;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/* The exit's side of the handshake: an entry that opens under the exit's sealing key is answered */
public class SessionAcceptTest {
	private static final String EXIT_ID = "gn-exit";
	private static final byte[] EXIT_KEY = SessionFixtures.sealingPrivate(EXIT_ID);
	private static final Connect CONNECT = new Connect(Socks5CommandType.CONNECT, new SocksAddress(
		Socks5AddressType.DOMAIN, "example.org", 443));
	private static final int SMALLEST_CLASS = 1024;
	private static final List<String> LOW_ORDER = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800");

	/* A client writer and the exit, each posting to a list of its own */
	private static final class Ends {
		private final AtomicLong clock = new AtomicLong(SessionFixtures.NOW);
		private final List<Entry> fromClient = new ArrayList<>();
		private final List<Entry> fromExit = new ArrayList<>();
		private final SessionContext client = SessionFixtures.context(1, fromClient, clock::get);
		private final SessionContext exit = SessionFixtures.context(2, fromExit, clock::get);

		/* An opening as a client posts it, sealed to the given key */
		private Initiation open(byte[] exitKey) throws HandshakeException {
			final Initiation initiation = NoiseNk.initiate(exitKey, Session.prologue(SessionFixtures.CHAIN,
				client.writer().writer()), new SecureRandom());

			client.post(Tag.random(new SecureRandom()), client.padded(initiation.message()));
			return initiation;
		}

		private Entry opening() {
			return fromClient.get(fromClient.size() - 1);
		}
	}

	/* Counts the draws, so that a test sees whether a refusal spent any */
	private static final class CountingRandom extends SecureRandom {
		private final AtomicInteger draws = new AtomicInteger();

		@Override
		public void nextBytes(byte[] bytes) {
			draws.incrementAndGet();
			super.nextBytes(bytes);
		}
	}

	private static Tag answerTag(Initiation initiation) {
		return Tags.of(initiation.state().answerTagKey(), Direction.EXIT_TO_CLIENT, 0);
	}

	@Example
	public void shouldAnswerAnOpeningSealedToItsKey() throws HandshakeException {
		final Ends ends = new Ends();
		final Initiation initiation = ends.open(SessionFixtures.sealingPublic(EXIT_ID));
		final Session session = Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY).orElseThrow();

		assertThat(ends.fromExit, hasSize(1));

		final Entry answer = ends.fromExit.get(0);

		assertThat(answer.header().kind(), is(EntryKind.SEALED));
		assertThat(answer.header().chainId(), equalTo(SessionFixtures.CHAIN));
		assertThat(answer.header().writer(), equalTo(ends.exit.writer().writer()));
		assertThat(answer.size(), is(SMALLEST_CLASS));
		assertThat(Session.tagOf(answer), equalTo(answerTag(initiation)));
		assertThat(session.peer(), equalTo(ends.client.writer().writer()));
		assertThat(session.isOpen(), is(true));
	}

	@Example
	public void shouldAgreeOnTheKeysWithTheClient() throws HandshakeException {
		final Ends ends = new Ends();
		final Initiation initiation = ends.open(SessionFixtures.sealingPublic(EXIT_ID));
		final Session exit = Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY).orElseThrow();
		final NoiseNk.Completed completed = initiation.state().complete(Arrays.copyOf(ends.fromExit.get(0).payload(),
			Session.ANSWER_SIZE));
		final Session client = new Session(ends.client, ends.exit.writer().writer(), Direction.CLIENT_TO_EXIT,
			completed.secrets());

		assertThat(completed.payload(), equalTo(ends.exit.writer().writer().bytes()));

		client.send(CONNECT);
		exit.send(new Data(new byte[] { 7 }));
		assertThat(exit.receive(ends.fromClient.get(ends.fromClient.size() - 1)), equalTo(Optional.of(CONNECT)));
		assertThat(client.receive(ends.fromExit.get(1)), equalTo(Optional.of(new Data(new byte[] { 7 }))));
	}

	@Example
	public void shouldLeaveAnOpeningForAnotherKeyAlone() throws HandshakeException {
		final Ends ends = new Ends();

		ends.open(SessionFixtures.sealingPublic("gn-other"));
		assertThat(Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY), equalTo(Optional.empty()));
		assertThat(ends.fromExit, hasSize(0));
	}

	@Example
	public void shouldLeaveAnythingThatIsNoOpeningAlone() {
		final Ends ends = new Ends();
		final Session client = new Session(ends.client, ends.exit.writer().writer(), Direction.CLIENT_TO_EXIT,
			new NoiseNk.SessionSecrets(new byte[32], new byte[32], new byte[32], new byte[32], new byte[32]));

		client.send(new Data(new byte[] { 1 }));
		ends.client.post(Tag.random(new SecureRandom()), new byte[SMALLEST_CLASS - Entry.OVERHEAD]);

		for (Entry entry : ends.fromClient) {
			assertThat(Session.tryAccept(ends.exit, entry, EXIT_KEY), equalTo(Optional.empty()));
		}

		assertThat(ends.fromExit, hasSize(0));
	}

	/* The prologue holds the chain and the writer of the opening */
	@Example
	public void shouldOpenAnOpeningOnlyUnderItsOwnWriterAndChain() throws HandshakeException {
		final Ends ends = new Ends();

		ends.open(SessionFixtures.sealingPublic(EXIT_ID));

		final byte[] payload = ends.opening().payload();
		final Entry otherWriter = SessionFixtures.context(3, new ArrayList<>(), ends.clock::get)
			.post(Tag.random(new SecureRandom()), payload);
		final ChainId otherChain = new ChainId(SessionFixtures.filled(ChainId.SIZE, 8));
		final SessionContext elsewhere = new SessionContext(new FeedWriter(SessionFixtures.key(1), otherChain),
			entry -> { }, ends.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);
		final Entry otherChainEntry = elsewhere.post(Tag.random(new SecureRandom()), payload);
		final SessionContext exitElsewhere = new SessionContext(new FeedWriter(SessionFixtures.key(2), otherChain),
			ends.fromExit::add, ends.clock::get, new SecureRandom(), ProxySettings.DEFAULTS);

		assertThat(Session.tryAccept(ends.exit, otherWriter, EXIT_KEY), equalTo(Optional.empty()));
		assertThat(Session.tryAccept(ends.exit, otherChainEntry, EXIT_KEY), equalTo(Optional.empty()));
		assertThat(Session.tryAccept(exitElsewhere, otherChainEntry, EXIT_KEY), equalTo(Optional.empty()));
		assertThat(Session.tryAccept(exitElsewhere, ends.opening(), EXIT_KEY), equalTo(Optional.empty()));
		assertThat(ends.fromExit, hasSize(0));
		assertThat(Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY).isPresent(), is(true));
	}

	/* An opening fills the smallest class, so nothing else is worth a key agreement */
	@Example
	public void shouldTryOnlyEntriesOfTheSmallestClass() throws HandshakeException {
		final Ends ends = new Ends();
		final Initiation initiation = NoiseNk.initiate(SessionFixtures.sealingPublic(EXIT_ID), Session.prologue(
			SessionFixtures.CHAIN, ends.client.writer().writer()), new SecureRandom());
		final byte[] message = initiation.message();

		for (int size : List.of(NoiseNk.MESSAGE_SIZE, SMALLEST_CLASS - Entry.OVERHEAD - 1,
			SMALLEST_CLASS - Entry.OVERHEAD + 1, 4096 - Entry.OVERHEAD)) {

			final Entry entry = ends.client.post(Tag.random(new SecureRandom()), Arrays.copyOf(message, size));

			assertThat("size " + size, Session.tryAccept(ends.exit, entry, EXIT_KEY), equalTo(Optional.empty()));
		}

		assertThat(ends.fromExit, hasSize(0));
	}

	@Example
	public void shouldRefuseAClientKeyOfLowOrder() throws HandshakeException {
		final Ends ends = new Ends();

		ends.open(SessionFixtures.sealingPublic(EXIT_ID));

		for (String point : LOW_ORDER) {
			final byte[] payload = ends.opening().payload();

			System.arraycopy(HexFormat.of().parseHex(point), 0, payload, 0, NoiseNk.KEY_SIZE);
			assertThat(point, Session.tryAccept(ends.exit, ends.client.post(Tag.random(new SecureRandom()), payload),
				EXIT_KEY), equalTo(Optional.empty()));
		}

		assertThat(ends.fromExit, hasSize(0));
	}

	@Example
	public void shouldAnswerWithAFreshKeyEachTime() throws HandshakeException {
		final Ends ends = new Ends();

		ends.open(SessionFixtures.sealingPublic(EXIT_ID));
		Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY).orElseThrow();
		Session.tryAccept(ends.exit, ends.opening(), EXIT_KEY).orElseThrow();

		assertThat(Arrays.copyOf(ends.fromExit.get(0).payload(), NoiseNk.KEY_SIZE),
			not(equalTo(Arrays.copyOf(ends.fromExit.get(1).payload(), NoiseNk.KEY_SIZE))));
		assertThat(Session.tagOf(ends.fromExit.get(0)), equalTo(Session.tagOf(ends.fromExit.get(1))));
	}

	@Example
	public void shouldSpendNoRandomnessOnWhatItRefuses() throws HandshakeException {
		final Ends ends = new Ends();
		final CountingRandom random = new CountingRandom();
		final SessionContext exit = new SessionContext(ends.exit.writer(), ends.fromExit::add, ends.clock::get,
			random, ProxySettings.DEFAULTS);

		ends.open(SessionFixtures.sealingPublic("gn-other"));
		assertThat(Session.tryAccept(exit, ends.opening(), EXIT_KEY), equalTo(Optional.empty()));
		assertThat(random.draws.get(), is(0));
	}

	@Example
	public void shouldRequireAPrivateKeyOfTheRightSize() throws HandshakeException {
		final Ends ends = new Ends();

		ends.open(SessionFixtures.sealingPublic(EXIT_ID));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> Session.tryAccept(ends.exit, ends.opening(), new byte[NoiseNk.KEY_SIZE - 1]));
		assertThat(ends.fromExit, hasSize(0));
	}
}
