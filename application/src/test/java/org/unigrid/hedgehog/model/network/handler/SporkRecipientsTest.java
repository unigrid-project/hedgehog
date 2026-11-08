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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import org.unigrid.hedgehog.model.network.schedule.PublishSporkSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SporkRecipientsTest {
	/* The types that release 0.0.8 reads: it cannot read any other, and a frame of one wrecks its stream. It knows the
	   type number of the vesting spork, but not the layout that spork has had since. */
	private static final Set<Type> READABLE_BY_RELEASE_0_0_8 = Set.of(Type.UNDEFINED, Type.MINT_STORAGE,
		Type.MINT_SUPPLY, Type.STATISTICS_PUBKEY);

	private static final List<Type> OF_THIS_RELEASE = List.of(Type.BOARD_KEYS, Type.VESTING_STORAGE, Type.STORAGE);

	/* The types that only a node run with --preview takes */
	private static final Set<Type> OF_THE_PREVIEW = Set.of(Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	private static final List<String> DAEMON = List.of("daemon");
	private static final String[] LEDGER_NODE = { Network.LEDGER_PROTOCOL, Network.PROXY_PROTOCOL,
		Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

	private static void withThePreview() {
		OptionsCli.parse(DAEMON, "--preview");
	}

	/* The sporks of these tests are signed by no one, and stand for sporks that count */
	@BeforeTry
	public void countEverySpork() {
		new MockUp<GridSpork>() {
			@Mock public boolean isCounting() {
				return true;
			}
		};
	}

	private static GridSpork spork(final Type type) {
		return GridSpork.create(type);
	}

	private static SporkDatabase databaseWithEverySpork() {
		final SporkDatabase database = SporkDatabase.builder().build();

		Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED).forEach(type -> database.set(spork(type)));
		return database;
	}

	/* One end of a connection between two nodes of this release, of which neither runs a ledger */
	private static EmbeddedChannel streamOfThisRelease() {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(LedgerProtocol.NEGOTIATED_KEY).set(false);
		connection.attr(SporkProtocol.NEGOTIATED_KEY).set(true);
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
	}

	@Example
	public void shouldNeedAProtocolOfThisReleaseForEveryTypeThatTheEarlierReleaseCannotRead() {
		for (final Type type : Type.values()) {
			assertThat(type.toString(), SporkRecipients.needsLedgerProtocol(type)
				|| SporkRecipients.needsThisRelease(type) || SporkRecipients.needsProxyProtocol(type),
				is(!READABLE_BY_RELEASE_0_0_8.contains(type)));
		}

		for (final Type type : OF_THIS_RELEASE) {
			assertThat(type.toString(), SporkRecipients.needsThisRelease(type), is(true));
		}

		assertThat(SporkRecipients.needsLedgerProtocol(Type.BOARD_KEYS), is(false));
		assertThat(SporkRecipients.needsLedgerProtocol(Type.COMMUNICATION), is(false));
		assertThat(SporkRecipients.needsThisRelease(Type.COMMUNICATION), is(false));
	}

	/* One end of a connection that agreed on a protocol, as a stream of it */
	@SneakyThrows
	private static List<Type> writtenOverAConnectionOf(final String[] server, final String[] client,
		final GridSpork spork) {

		try (QuicLoopback loopback = QuicLoopback.between(server, client)) {
			final EmbeddedChannel stream = new EmbeddedChannel(loopback.clientEnd(), DefaultChannelId.newInstance(),
				true, false);

			SporkRecipients.write(stream, spork);
			return sporkTypesWrittenTo(stream);
		}
	}

	/* Chain traffic goes with it, so only a connection that settled on the proxy protocol, or on the ledger
	   protocol that carries it, gets the communication spork */
	@Example
	public void shouldWriteTheCommunicationSporkOnlyToAConnectionThatSpeaksTheProxy() {
		final String[] proxyNode = { Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0],
			PREVIOUS_RELEASE[1] };
		final String[] withoutProxy = { Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };
		final GridSpork communication = spork(Type.COMMUNICATION);

		withThePreview();
		assertThat(writtenOverAConnectionOf(LEDGER_NODE, LEDGER_NODE, communication), contains(Type.COMMUNICATION));
		assertThat(writtenOverAConnectionOf(proxyNode, LEDGER_NODE, communication), contains(Type.COMMUNICATION));
		assertThat(writtenOverAConnectionOf(proxyNode, proxyNode, communication), contains(Type.COMMUNICATION));
		assertThat(writtenOverAConnectionOf(withoutProxy, proxyNode, communication), empty());
		assertThat(writtenOverAConnectionOf(PREVIOUS_RELEASE, proxyNode, communication), empty());
	}

	@SneakyThrows
	@Example
	public void shouldGiveTheScheduleTheCommunicationSporkOnlyWhereTheOtherEndTakesIt() {
		final SporkDatabase database = databaseWithEverySpork();

		withThePreview();

		try (QuicLoopback loopback = QuicLoopback.between(Network.getProtocols(), Network.getProtocols())) {
			final EmbeddedChannel proxy = new EmbeddedChannel(loopback.clientEnd(), DefaultChannelId.newInstance(),
				true, false);

			PublishSporkSchedule.writeAndFlush(proxy, database, new PendingSporks());
			assertThat(sporkTypesWrittenTo(proxy), contains(Type.BOARD_KEYS, Type.MINT_STORAGE, Type.MINT_SUPPLY,
				Type.VESTING_STORAGE, Type.STORAGE, Type.COMMUNICATION));
		}
	}

	/* A node of release 0.0.8 cannot read the board keys, the storage spork or the vesting spork as it is now, and
	   every node of this release can */
	@Example
	public void shouldWriteTheSporksOfThisReleaseOnlyToAStreamOfThisRelease() {
		withThePreview();

		for (final Type type : OF_THIS_RELEASE) {
			final EmbeddedChannel release = streamOfThisRelease();
			final EmbeddedChannel ledger = stream(true);
			final EmbeddedChannel earlier = stream(false);

			for (final EmbeddedChannel stream : List.of(release, ledger, earlier)) {
				SporkRecipients.write(stream, spork(type));
			}

			assertThat(type.toString(), sporkTypesWrittenTo(release), contains(type));
			assertThat(type.toString(), sporkTypesWrittenTo(ledger), contains(type));
			assertThat(type.toString(), sporkTypesWrittenTo(earlier), empty());
		}
	}

	@Example
	public void shouldWriteTheSporksOfThisReleaseOnlyToAConnectionThatSettledOnTheProtocolOfThisRelease() {
		final String[] withoutProxy = { Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

		withThePreview();

		for (final Type type : OF_THIS_RELEASE) {
			assertThat(type.toString(), writtenOverAConnectionOf(withoutProxy, withoutProxy, spork(type)),
				contains(type));
			assertThat(type.toString(), writtenOverAConnectionOf(PREVIOUS_RELEASE, Network.getProtocols(),
				spork(type)), empty());
		}
	}

	@Example
	public void shouldWriteALedgerSporkOnlyToAStreamThatSettledOnTheLedgerProtocol() {
		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel earlier = stream(false);

		withThePreview();

		for (final Type type : List.of(Type.LEDGER_BLACKLIST, Type.LEDGER_FEE)) {
			SporkRecipients.write(ledger, spork(type));
			SporkRecipients.write(earlier, spork(type));
		}

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_BLACKLIST, Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());
	}

	@Example
	public void shouldWriteAnEarlierSporkToEveryStream() {
		for (final Type type : READABLE_BY_RELEASE_0_0_8) {
			if (type != Type.UNDEFINED) {
				final EmbeddedChannel ledger = stream(true);
				final EmbeddedChannel earlier = stream(false);

				SporkRecipients.write(ledger, spork(type));
				SporkRecipients.write(earlier, spork(type));

				assertThat(sporkTypesWrittenTo(ledger), contains(type));
				assertThat(sporkTypesWrittenTo(earlier), contains(type));
			}
		}
	}

	@Example
	public void shouldWriteNothingForASporkThatIsNotSet() {
		final EmbeddedChannel ledger = stream(true);

		SporkRecipients.write(ledger, null);

		assertThat(sporkTypesWrittenTo(ledger), empty());
		assertThat(SporkRecipients.mayReceive(ledger, null), is(false));
	}

	/* The board keys first, as every other spork is judged by the keys they make active */
	@Example
	public void shouldGiveTheScheduleAndTheNewStreamsEachSporkThatTheOtherEndReads() {
		final SporkDatabase database = databaseWithEverySpork();
		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel release = streamOfThisRelease();
		final EmbeddedChannel earlier = stream(false);

		withThePreview();

		for (final EmbeddedChannel stream : List.of(ledger, release, earlier)) {
			PublishSporkSchedule.writeAndFlush(stream, database, new PendingSporks());
		}

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.BOARD_KEYS, Type.MINT_STORAGE, Type.MINT_SUPPLY,
			Type.VESTING_STORAGE, Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(release), contains(Type.BOARD_KEYS, Type.MINT_STORAGE, Type.MINT_SUPPLY,
			Type.VESTING_STORAGE, Type.STORAGE));
		assertThat(sporkTypesWrittenTo(earlier), contains(Type.MINT_STORAGE, Type.MINT_SUPPLY));
	}

	/* Not even to a stream whose other end runs the ledger and the proxy, and so reads every type of this release */
	@Provide
	public Arbitrary<Type> previewTypes() {
		return Arbitraries.of(OF_THE_PREVIEW);
	}

	@Property
	public void shouldWriteASporkOfThePreviewToNoStreamWithoutThePreview(@ForAll("previewTypes") Type type) {
		final GridSpork spork = spork(type);

		OptionsCli.parse(DAEMON);

		for (final EmbeddedChannel stream : List.of(stream(true), streamOfThisRelease(), stream(false))) {
			SporkRecipients.write(stream, spork);
			assertThat(type.toString(), SporkRecipients.mayReceive(stream, spork), is(false));
			assertThat(type.toString(), sporkTypesWrittenTo(stream), empty());
		}

		assertThat(type.toString(), writtenOverAConnectionOf(LEDGER_NODE, LEDGER_NODE, spork), empty());
	}

	@SneakyThrows
	@Example
	public void shouldGiveTheScheduleOnlyTheSporksOfThisReleaseWithoutThePreview() {
		final SporkDatabase database = databaseWithEverySpork();
		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel release = streamOfThisRelease();

		OptionsCli.parse(DAEMON);

		try (QuicLoopback loopback = QuicLoopback.between(LEDGER_NODE, LEDGER_NODE)) {
			final EmbeddedChannel proxy = new EmbeddedChannel(loopback.clientEnd(), DefaultChannelId.newInstance(),
				true, false);

			for (final EmbeddedChannel stream : List.of(ledger, release, proxy)) {
				PublishSporkSchedule.writeAndFlush(stream, database, new PendingSporks());
				assertThat(sporkTypesWrittenTo(stream), contains(Type.BOARD_KEYS, Type.MINT_STORAGE,
					Type.MINT_SUPPLY, Type.VESTING_STORAGE));
			}
		}
	}

	@Example
	public void shouldNotWriteTheSporksThatAreNotSet() {
		final EmbeddedChannel ledger = stream(true);
		final SporkDatabase database = SporkDatabase.builder().build();

		database.set(spork(Type.MINT_SUPPLY));
		PublishSporkSchedule.writeAndFlush(ledger, database, new PendingSporks());

		assertThat(sporkTypesWrittenTo(ledger), equalTo(List.of(Type.MINT_SUPPLY)));
	}
}
