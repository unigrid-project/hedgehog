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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainHostResendTest {
	private static final int EARLIER = 10_000;
	private static final int UNTIL_THE_CROWD_EXPIRES = 21_000;
	private static final int WRITER = 1;
	private static final int OTHER_WRITER = 2;
	private static final int CLIENT_WRITER = 3;
	private static final int FEED = 5;
	private static final String CLIENT = "client";
	private static final List<String> OTHER_MEMBERS = List.of("gn-0", "gn-2", "gn-3");
	private static final Runnable NOTHING = () -> { };

	/* A stream that runs what it is given when the next packet is written to it, before the packet goes out */
	private static final class HookedStream extends EmbeddedChannel {
		private final AtomicReference<Runnable> beforeWrite = new AtomicReference<>(NOTHING);

		@Override
		public ChannelFuture write(final Object message) {
			beforeWrite.getAndSet(NOTHING).run();
			return super.write(message);
		}
	}

	/* gn-1 of a group of four, which reaches the other members over streams the test reads itself, with a client
	   attached as CLIENT_WRITER, and whose deliveries are run by the executor it was given */
	private record Lone(ChainHost host, ChainId chainId, Map<String, HookedStream> members, EmbeddedChannel client) {
		static Lone over(final Executor delivery) {
			final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
			final Map<String, HookedStream> members = new LinkedHashMap<>();

			OTHER_MEMBERS.forEach(member -> members.put(member, new HookedStream()));
			fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, 3)));

			final ChainHost host = new ChainHost(fleet.settings::get,
				new TopologyGridnodeDirectory(() -> fleet.gridnodes, () -> "gn-1"),
				peer -> Optional.<Channel>ofNullable(members.get(peer)), fleet.clock::get, fleet.random,
				new ManualScheduler(), delivery);
			final EmbeddedChannel client = new EmbeddedChannel();

			host.refresh();

			final ChainId chainId = host.hostedChains().get(0);

			host.join(client, JoinChain.builder().requestId(1).writer(ChainFleet.writer(CLIENT_WRITER))
				.chainId(chainId).build());

			return new Lone(host, chainId, members, client);
		}

		List<Entry> feed(final int count) {
			return ChainFleet.entries(ChainFleet.key(WRITER), chainId, count, ChainFleet.NOW);
		}

		List<EmbeddedChannel> audience() {
			return Stream.concat(members.values().stream(), Stream.of(client)).toList();
		}

		/* Every stream forgets what was written to it so far */
		void forget() {
			audience().forEach(ChainHostResendTest::written);
		}

		void refuse(final Entry entry) {
			host.refused(members.get("gn-0"), EntryRefused.of(entry, Admission.FULL));
		}

		void resend() {
			host.resend(chainId, ChainFleet.writer(WRITER));
		}

		boolean isPaused() {
			return host.pauses().isPaused(chainId, ChainFleet.writer(WRITER));
		}
	}

	/* The entries flooded to a stream since it was last read, in the order they were written */
	private static List<Entry> written(final EmbeddedChannel stream) {
		final List<Entry> entries = new ArrayList<>();

		for (Object packet = stream.readOutbound(); packet != null; packet = stream.readOutbound()) {
			if (packet instanceof ChainEntry batch) {
				entries.addAll(batch.getEntries());
			}
		}

		return entries;
	}

	private static void runAll(final List<Runnable> handedOver) {
		while (!handedOver.isEmpty()) {
			handedOver.remove(0).run();
		}
	}

	/* The members other than gn-1 hold a full chain of an earlier writer, so they refuse what gn-1 posts */
	private static Entry postedIntoFullMembers(final ChainFleet fleet) {
		final List<Entry> crowd = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 5, ChainFleet.NOW - EARLIER);
		final Entry mine = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);

		fleet.offline("gn-1");
		fleet.refreshAll();
		fleet.host("gn-0").receive(fleet.client("gn-0", 2), ChainEntry.builder().entries(crowd).build());
		fleet.deliver();
		fleet.online("gn-1");
		fleet.refreshAll();
		assertThat(fleet.host("gn-1").post(mine), is(Admission.ACCEPTED));
		fleet.deliver();
		return mine;
	}

	private static void expireTheCrowd(final ChainFleet fleet) {
		fleet.clock.addAndGet(UNTIL_THE_CROWD_EXPIRES);
		IntStream.of(0, 2, 3).forEach(member -> fleet.host("gn-" + member).maintain());
	}

	/* Joins the first chain at a member through the member's end of its stream to the client, then follows it
	   through the client's end */
	private static void follow(final ChainFleet fleet, final String member) {
		final JoinReply reply = fleet.host(member).join(fleet.stream(member, CLIENT), JoinChain.builder()
			.requestId(1).writer(ChainFleet.writer(WRITER)).chainId(fleet.chain()).build());

		assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(fleet.host(CLIENT).follow(fleet.chain(), fleet.stream(CLIENT, member), reply.getHeads()),
			is(true));
	}

	@Example
	public void offersTheRefusedEntriesAgainOnceTheMembersHaveRoom() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry mine = postedIntoFullMembers(fleet);
		final ChainHost host = fleet.host("gn-1");

		assertThat(host.pauses().isPaused(fleet.chain(), mine.header().writer()), is(true));
		assertThat(fleet.host("gn-0").chain(fleet.chain()).orElseThrow().entries().contains(mine), is(false));

		expireTheCrowd(fleet);
		host.resend(fleet.chain(), mine.header().writer());
		fleet.deliver();

		assertThat(host.pauses().isPaused(fleet.chain(), mine.header().writer()), is(false));

		for (final int member : List.of(0, 2, 3)) {
			assertThat(fleet.host("gn-" + member).chain(fleet.chain()).orElseThrow().entries(), hasItem(mine));
		}
	}

	@Example
	public void staysQuietForAWriterNobodyPaused() {
		final ChainFleet fleet = ChainFleet.group();
		final Entry mine = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW).get(0);

		assertThat(fleet.host("gn-1").post(mine), is(Admission.ACCEPTED));
		fleet.deliver();

		final int delivered = fleet.deliveries.size();

		fleet.host("gn-1").resend(fleet.chain(), mine.header().writer());
		fleet.deliver();
		assertThat(fleet.deliveries.size(), equalTo(delivered));
	}

	@Example
	public void letsTheWriterGoWhenTheChainIsGone() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final Entry mine = postedIntoFullMembers(fleet);
		final ChainHost host = fleet.host("gn-1");

		fleet.settings.set(Optional.empty());
		host.refresh();
		host.resend(fleet.chain(), mine.header().writer());

		assertThat(host.pauses().isPaused(fleet.chain(), mine.header().writer()), is(false));
	}

	@Property
	public void offersTheRetainedEntriesFromTheRefusedOneOnToEveryMemberAndClient(
		@ForAll @IntRange(max = FEED - 1) int refusedAt) {

		final Lone lone = Lone.over(Runnable::run);
		final List<Entry> entries = lone.feed(FEED);
		final List<Entry> heard = new CopyOnWriteArrayList<>();

		lone.host().addEntryListener((chainId, entry) -> heard.add(entry));
		entries.forEach(lone.host()::post);
		lone.forget();
		lone.refuse(entries.get(refusedAt));
		lone.resend();

		for (final EmbeddedChannel stream : lone.audience()) {
			assertThat(written(stream), equalTo(entries.subList(refusedAt, FEED)));
		}

		assertThat(lone.isPaused(), is(false));
		assertThat(heard, is(empty()));
	}

	@Example
	public void keepsARefusalThatTheResentEntriesDrawWhileTheyGoOut() {
		final Lone lone = Lone.over(Runnable::run);
		final List<Entry> entries = lone.feed(FEED);
		final HookedStream member = lone.members().get("gn-2");

		entries.forEach(lone.host()::post);
		lone.forget();
		lone.refuse(entries.get(2));
		member.beforeWrite.set(() -> lone.host().refused(member, EntryRefused.of(entries.get(3), Admission.FULL)));
		lone.resend();

		assertThat(lone.host().pauses().refusedFrom(lone.chainId(), ChainFleet.writer(WRITER)),
			is(OptionalLong.of(3)));
	}

	@Example
	public void goesOutBehindWhatTheChainQueuedBeforeAndOnlyOnTheDeliveryExecutor() {
		final List<Runnable> handedOver = new ArrayList<>();
		final Lone lone = Lone.over(handedOver::add);
		final List<Entry> entries = lone.feed(FEED + 1);
		final Entry later = entries.get(FEED);

		entries.subList(0, FEED).forEach(lone.host()::post);
		runAll(handedOver);
		lone.forget();
		lone.refuse(entries.get(2));
		lone.host().post(later);
		lone.resend();

		for (final EmbeddedChannel stream : lone.audience()) {
			assertThat(written(stream), is(empty()));
		}

		runAll(handedOver);

		for (final EmbeddedChannel stream : lone.audience()) {
			assertThat(written(stream), contains(later, entries.get(2), entries.get(3), entries.get(4), later));
		}
	}

	@Example
	public void liftsThePauseOfThatWriterOnThatChainAlone() {
		final Lone lone = Lone.over(Runnable::run);
		final ChainId otherChain = lone.host().hostedChains().get(1);
		final EmbeddedChannel member = lone.members().get("gn-0");
		final List<Entry> entries = lone.feed(FEED);

		entries.forEach(lone.host()::post);
		lone.refuse(entries.get(0));
		lone.host().refused(member, EntryRefused.builder().chainId(lone.chainId())
			.writer(ChainFleet.writer(OTHER_WRITER)).seq(0).admission(Admission.FULL).build());
		lone.host().refused(member, EntryRefused.builder().chainId(otherChain).writer(ChainFleet.writer(WRITER))
			.seq(0).admission(Admission.FULL).build());
		lone.resend();

		assertThat(lone.isPaused(), is(false));
		assertThat(lone.host().pauses().isPaused(lone.chainId(), ChainFleet.writer(OTHER_WRITER)), is(true));
		assertThat(lone.host().pauses().isPaused(otherChain, ChainFleet.writer(WRITER)), is(true));
	}

	@Example
	public void drawsNothingFromMembersThatHaveTheEntriesAlready() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-1");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(WRITER), fleet.chain(), FEED, ChainFleet.NOW);

		entries.forEach(host::post);
		fleet.deliver();
		host.refused(fleet.stream("gn-1", "gn-0"), EntryRefused.of(entries.get(0), Admission.FULL));
		fleet.deliveries.clear();
		host.resend(fleet.chain(), ChainFleet.writer(WRITER));
		fleet.deliver();

		assertThat(fleet.deliveries.stream().map(ChainFleet.Delivery::from).toList(),
			contains("gn-1", "gn-1", "gn-1"));
		assertThat(fleet.agree(fleet.chain()), is(true));
	}

	@Example
	public void offersAFollowedChainAgainThroughEveryMemberItFollows() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(WRITER), fleet.chain(), FEED, ChainFleet.NOW);

		follow(fleet, "gn-0");
		follow(fleet, "gn-2");
		entries.forEach(client::post);
		fleet.deliver();
		client.refused(fleet.stream(CLIENT, "gn-2"), EntryRefused.of(entries.get(1), Admission.FULL));
		client.resend(fleet.chain(), ChainFleet.writer(WRITER));

		assertThat(written(fleet.stream(CLIENT, "gn-0")), equalTo(entries.subList(1, FEED)));
		assertThat(written(fleet.stream(CLIENT, "gn-2")), equalTo(entries.subList(1, FEED)));
		assertThat(client.pauses().isPaused(fleet.chain(), ChainFleet.writer(WRITER)), is(false));
	}

	@Example
	public void letsTheWriterGoOfAChainItNoLongerFollows() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost client = fleet.host(CLIENT);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(WRITER), fleet.chain(), FEED, ChainFleet.NOW);

		follow(fleet, "gn-0");
		entries.forEach(client::post);
		fleet.deliver();
		client.refused(fleet.stream(CLIENT, "gn-0"), EntryRefused.of(entries.get(0), Admission.FULL));
		client.unfollow(fleet.chain());
		client.resend(fleet.chain(), ChainFleet.writer(WRITER));

		assertThat(client.pauses().isPaused(fleet.chain(), ChainFleet.writer(WRITER)), is(false));
		assertThat(written(fleet.stream(CLIENT, "gn-0")), is(empty()));
	}

	@Example
	public void letsTheWriterGoOfAChainItNeverHeld() {
		final ChainHost host = ChainFleet.group().host("gn-1");
		final ChainId unknown = new ChainId(new byte[ChainId.SIZE]);

		host.pauses().pause(EntryRefused.builder().chainId(unknown).writer(ChainFleet.writer(WRITER)).seq(0)
			.admission(Admission.FULL).build());
		host.resend(unknown, ChainFleet.writer(WRITER));

		assertThat(host.pauses().isPaused(unknown, ChainFleet.writer(WRITER)), is(false));
	}

	@Example
	public void neverThrowsForAMissingChainOrWriter() {
		final ChainHost host = ChainFleet.group().host("gn-1");
		final WriterId writer = ChainFleet.writer(WRITER);

		host.pauses().pause(EntryRefused.builder().writer(writer).seq(0).admission(Admission.FULL).build());
		host.resend(null, null);
		host.resend(null, writer);
		host.resend(host.hostedChains().get(0), null);

		assertThat(host.pauses().isPaused(null, writer), is(false));
	}

	@Example
	public void liftsThePauseOnAHostThatIsStoppedWithoutThrowing() {
		final Lone lone = Lone.over(Executors.newSingleThreadExecutor());
		final List<Entry> entries = lone.feed(FEED);

		lone.host().stop();
		entries.forEach(lone.host()::post);
		lone.refuse(entries.get(0));
		lone.resend();

		assertThat(lone.isPaused(), is(false));

		for (final EmbeddedChannel stream : lone.audience()) {
			assertThat(written(stream), is(empty()));
		}
	}

	@Example
	public void warnsWhenTheQueueOfTheChainHasNoRoomAndStillLetsTheWriterGo() {
		final List<Runnable> handedOver = new ArrayList<>();
		final Lone lone = Lone.over(handedOver::add);
		final List<Entry> entries = lone.feed(FEED);

		final HostedChain hosted = lone.host().hosted(lone.chainId()).orElseThrow();

		hosted.setDeliveries(new DeliveryQueue(new LinkedBlockingQueue<>(1)));
		lone.host().post(entries.get(0));
		lone.refuse(entries.get(0));

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(lone::resend);

		assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage).toList(),
			contains("A chain delivery queue is full"));
		assertThat(lone.isPaused(), is(false));
		runAll(handedOver);

		for (final EmbeddedChannel stream : lone.audience()) {
			assertThat(written(stream), contains(entries.get(0)));
		}
	}
}
