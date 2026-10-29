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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostStemTest {
	private static final long NOW = ChainFleet.NOW;
	private static final String RELAY = "gn-0";
	private static final List<String> OTHERS = List.of("gn-1", "gn-2", "gn-3");
	private static final int MAX_ROUNDS = 25;

	/* What a stemmed entry is answered: the first of a writer is taken, the later ones wait for it past a gap */
	private static final Map<Boolean, Admission> PASSED_ON = Map.of(true, Admission.ACCEPTED, false, Admission.AHEAD);

	private static ChainFleet group(final Function<String, RandomGenerator> stems) {
		return group(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, 3), stems);
	}

	private static ChainFleet group(final ProxySettings settings, final Function<String, RandomGenerator> stems) {
		return ChainFleet.group(ChainFleet.MEMBERS, settings, stems);
	}

	private static List<Entry> entries(final ChainFleet fleet, final int writer, final int count) {
		return ChainFleet.entries(ChainFleet.key(writer), fleet.chain(), count, NOW);
	}

	private static HostedChain hosted(final ChainFleet fleet, final String member) {
		return fleet.host(member).hosted(fleet.chain()).orElseThrow();
	}

	private static List<EntryRefused> stem(final ChainFleet fleet, final String to, final String from,
		final List<Entry> entries) {

		return fleet.host(to).stem(fleet.stream(to, from), new ChainStem(entries));
	}

	/* What a member has written to each of the others so far, taken off the streams */
	private static Map<String, List<Object>> writtenBy(final ChainFleet fleet, final String member) {
		final Map<String, List<Object>> written = new LinkedHashMap<>();

		for (final String peer : OTHERS.contains(member) ? others(member) : OTHERS) {
			final List<Object> packets = new ArrayList<>();
			final EmbeddedChannel stream = fleet.stream(member, peer);

			for (Object packet = stream.readOutbound(); packet != null; packet = stream.readOutbound()) {
				packets.add(packet);
			}

			written.put(peer, packets);
		}

		return written;
	}

	private static List<String> others(final String member) {
		final List<String> all = new ArrayList<>(List.of(RELAY));

		all.addAll(OTHERS);
		all.remove(member);
		return all;
	}

	private static List<Object> all(final Map<String, List<Object>> written) {
		return written.values().stream().flatMap(List::stream).toList();
	}

	@Example
	public void aRelayHoldsAStemAndPassesItOnToAnotherMember() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 2);

		assertThat(stem(fleet, RELAY, "gn-1", entries), is(empty()));

		final Map<String, List<Object>> written = writtenBy(fleet, RELAY);
		final List<Object> stems = all(written);

		assertThat(entries.stream().allMatch(hosted(fleet, RELAY).getEmbargo()::holds), is(true));
		assertThat(hosted(fleet, RELAY).getChain().entries(), is(empty()));
		assertThat(written.get("gn-1"), is(empty()));
		assertThat(stems, equalTo(List.of(new ChainStem(entries))));
	}

	/* The diffuser floods the stem to every member, the one it came from too, which waits for it to come back */
	@Example
	public void aDiffuserFloodsAStemToEveryMember() {
		final ChainFleet fleet = group(ChainFleet.everyMember(true));
		final List<Entry> entries = entries(fleet, 1, 2);

		assertThat(stem(fleet, RELAY, "gn-1", entries), is(empty()));
		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(entries));
		assertThat(hosted(fleet, RELAY).getEmbargo().bytes(), is(0L));
		fleet.deliver();

		assertThat(fleet.agree(fleet.chain()), is(true));
		assertThat(hosted(fleet, "gn-1").getChain().entries(), equalTo(entries));
	}

	/* Every member that held the entry has passed it on already, so it goes round no more */
	@Example
	public void aStemThatCameRoundGoesNoFurther() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 1);

		stem(fleet, RELAY, "gn-1", entries);
		writtenBy(fleet, RELAY);
		stem(fleet, RELAY, "gn-2", entries);

		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		fleet.deliver();
		assertThat(fleet.agree(fleet.chain()), is(true));
		assertThat(hosted(fleet, RELAY).getChain().entries(), is(empty()));
	}

	/* A client passes nothing on, so a stem from its stream, from a stranger or for a chain this node only follows is
	   dropped like a fetch from one */
	@Example
	public void takesAStemFromAMemberOnly() {
		final ChainFleet fleet = group(ChainFleet.everyMember(true));
		final EmbeddedChannel client = fleet.client(RELAY, 1);
		final List<Entry> entries = entries(fleet, 1, 1);
		final ChainHost follower = fleet.host("client");
		final EmbeddedChannel followedThrough = fleet.stream("client", RELAY);

		assertThat(follower.follow(fleet.chain(), followedThrough, Map.of()), is(true));
		assertThat(fleet.host(RELAY).stem(client, new ChainStem(entries)), is(empty()));
		assertThat(fleet.host(RELAY).stem(new EmbeddedChannel(), new ChainStem(entries)), is(empty()));
		assertThat(follower.stem(followedThrough, new ChainStem(entries)), is(empty()));

		assertThat(hosted(fleet, RELAY).getChain().entries(), is(empty()));
		assertThat(follower.chain(fleet.chain()).orElseThrow().entries(), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(client.outboundMessages(), is(empty()));
	}

	@Example
	public void aStemCountsAgainstTheEntriesOfAFlood() {
		final ChainFleet fleet = group(ChainFleet.everyMember(true));
		final List<Entry> entries = entries(fleet, 1, 2);
		final EmbeddedChannel member = fleet.stream(RELAY, "gn-1");

		fleet.host(RELAY).receive(member, ChainEntry.builder().entries(Collections.nCopies(
			StreamBudgets.Kind.ENTRIES.getPerSecond(), entries.get(0))).build());
		stem(fleet, RELAY, "gn-1", entries.subList(1, 2));

		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(entries.subList(0, 1)));
		fleet.clock.addAndGet(1000);
		stem(fleet, RELAY, "gn-1", entries.subList(1, 2));
		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(entries));
	}

	/* A chain of room for two entries holds none yet, but the relay holds two in its embargo that it will take in once
	   they come back, so it has no room for a third */
	@Example
	public void refusesAStemTheChainHasNoRoomForCountingItsEmbargo() {
		final ChainFleet fleet = group(ChainFleet.smallChains(2), ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 3);

		assertThat(stem(fleet, RELAY, "gn-1", entries.subList(0, 2)), is(empty()));
		assertThat(stem(fleet, RELAY, "gn-1", entries.subList(2, 3)),
			equalTo(List.of(EntryRefused.of(entries.get(2), Admission.FULL))));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(entries.get(2)), is(false));
	}

	/* An entry that comes back by flood is in the chain, and the embargo has nothing left to flood */
	@Example
	public void letsGoOfWhatCameBack() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 1);

		stem(fleet, RELAY, "gn-1", entries);
		writtenBy(fleet, RELAY);
		fleet.host(RELAY).receive(fleet.stream(RELAY, "gn-2"), new ChainEntry(entries));
		writtenBy(fleet, RELAY);

		assertThat(hosted(fleet, RELAY).getEmbargo().bytes(), is(0L));
		fleet.clock.addAndGet(20_000);
		fleet.host(RELAY).maintain();
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
	}

	/* The successor dropped the stem, so the relay floods it once its deadline is past, to every member */
	@Example
	public void floodsWhatTheEmbargoHeldPastItsDeadline() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 1);
		int rounds = 0;

		stem(fleet, RELAY, "gn-1", entries);
		writtenBy(fleet, RELAY);

		while (hosted(fleet, RELAY).getChain().entries().isEmpty() && rounds < MAX_ROUNDS) {
			fleet.clock.addAndGet(1000);
			fleet.host(RELAY).maintain();
			rounds++;
		}

		final Map<String, List<Object>> written = writtenBy(fleet, RELAY);

		assertThat(rounds, is(greaterThanOrEqualTo((int) (StemEmbargo.RELAY_MILLIS / 1000))));
		assertThat(rounds, is(lessThan(MAX_ROUNDS)));
		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(entries));
		assertThat(written.values(), everyItem(hasSize(1)));
		assertThat(all(written), everyItem(is(instanceOf(ChainEntry.class))));
	}

	@Example
	public void neverStemsToTheStreamOfAClient() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final EmbeddedChannel client = fleet.client(RELAY, 1);

		for (int writer = 2; writer < 30; writer++) {
			stem(fleet, RELAY, "gn-1", entries(fleet, writer, 1));
		}

		assertThat(client.outboundMessages(), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), everyItem(is(instanceOf(ChainStem.class))));
	}

	/* The exit's own entries, which no member has seen yet */
	private static final String EXIT = "gn-1";

	private static Admission postStem(final ChainFleet fleet, final Entry entry) {
		return fleet.host(EXIT).postStem(fleet.chain(), entry, Set.of());
	}

	/* The member stream of the exit that the stem of a writer went to, with what went there */
	private static Map.Entry<String, List<Object>> stemmedTo(final Map<String, List<Object>> written) {
		final List<Map.Entry<String, List<Object>>> stemmed = written.entrySet().stream()
			.filter(sent -> !sent.getValue().isEmpty()).toList();

		assertThat(stemmed, hasSize(1));
		assertThat(stemmed.get(0).getValue(), everyItem(is(instanceOf(ChainStem.class))));
		return stemmed.get(0);
	}

	@Example
	public void theExitsHeadsHideEntriesStillInTheStem() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> other = entries(fleet, 2, 1);
		final ChainHost exit = fleet.host(EXIT);
		final FetchEntries request = FetchEntries.builder().chainId(fleet.chain())
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 1))).build();

		own.forEach(entry -> assertThat(postStem(fleet, entry), is(PASSED_ON.get(entry.header().seq() == 0))));
		exit.receive(fleet.stream(EXIT, RELAY), new ChainEntry(other));
		writtenBy(fleet, EXIT);
		exit.reconcileRound();

		final ChainHeads announced = (ChainHeads) all(writtenBy(fleet, EXIT)).get(0);

		assertThat(exit.chain(fleet.chain()).orElseThrow().heads().keySet(), is(Set.of(ChainFleet.writer(2))));
		assertThat(announced.getHeads().keySet(), is(Set.of(ChainFleet.writer(2))));
		assertThat(exit.fetch(fleet.stream(EXIT, RELAY), request), is(empty()));
		assertThat(exit.join(fleet.client(EXIT, 3), JoinChain.builder().requestId(1).writer(ChainFleet.writer(3))
			.chainId(fleet.chain()).build()).getHeads().keySet(), is(Set.of(ChainFleet.writer(2))));

		exit.receive(fleet.stream(EXIT, "gn-2"), new ChainEntry(own));

		assertThat(exit.chain(fleet.chain()).orElseThrow().heads().keySet(),
			is(Set.of(ChainFleet.writer(1), ChainFleet.writer(2))));
		assertThat(exit.fetch(fleet.stream(EXIT, RELAY), request), equalTo(own));
		assertThat(hosted(fleet, EXIT).getEmbargo().bytes(), is(0L));
	}

	@Example
	public void aDroppedStemIsFloodedByTheEmbargo() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		int rounds = 0;

		postStem(fleet, own.get(0));
		stemmedTo(writtenBy(fleet, EXIT));

		while (hosted(fleet, EXIT).getChain().entries().isEmpty() && rounds < MAX_ROUNDS) {
			fleet.clock.addAndGet(1000);
			fleet.host(EXIT).maintain();
			rounds++;
		}

		final Map<String, List<Object>> written = writtenBy(fleet, EXIT);

		assertThat(rounds, is(greaterThanOrEqualTo((int) (StemEmbargo.OWN_MILLIS / 1000))));
		assertThat(rounds, is(lessThan(MAX_ROUNDS)));
		assertThat(written.keySet(), is(Set.copyOf(others(EXIT))));
		assertThat(written.values(), everyItem(equalTo(List.of(new ChainEntry(own)))));
	}

	/* The replica of the exit has room for two entries, which the stem holds, so the third waits */
	@Example
	public void stemsNoEntryTheReplicaWouldHaveNoRoomFor() {
		final ChainFleet fleet = group(ChainFleet.smallChains(2), ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);

		assertThat(postStem(fleet, own.get(0)), is(Admission.ACCEPTED));
		assertThat(postStem(fleet, own.get(1)), is(Admission.AHEAD));
		writtenBy(fleet, EXIT);
		assertThat(postStem(fleet, own.get(2)), is(Admission.FULL));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(2)), is(false));
	}

	/* A writer whose entries are all in the stem has a place on the chain as if they were in it */
	@Example
	public void aWriterInTheStemHoldsAPlace() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final HostedChain hosted = new HostedChain(new Chain(fleet.chain(), ProxySettings.DEFAULTS.chainLimits(),
			fleet.clock::get), new Epoch(0), 1, new StemEmbargo(new Random(1)));

		assertThat(hosted.holdOwn(entries(fleet, 1, 1).get(0), NOW), is(Admission.ACCEPTED));
		assertThat(hosted.takesNewWriters(), is(false));
		assertThat(hosted.holdOwn(entries(fleet, 2, 1).get(0), NOW), is(Admission.FULL));
		assertThat(hosted.getEmbargo().holdsAnyOf(ChainFleet.writer(2)), is(false));
	}

	/* What a member refused is stemmed again while the stem still holds it, and what came back already is left to
	   reconcile: an exit that flooded its own entries again would show the member which refused them whose they are */
	@Example
	public void resendsThroughTheStemWhatItStillHolds() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final ChainHost exit = fleet.host(EXIT);

		own.forEach(entry -> postStem(fleet, entry));

		final Map.Entry<String, List<Object>> successor = stemmedTo(writtenBy(fleet, EXIT));
		final EmbeddedChannel stream = fleet.stream(EXIT, successor.getKey());

		exit.receive(fleet.stream(EXIT, RELAY.equals(successor.getKey()) ? "gn-2" : RELAY),
			new ChainEntry(own.subList(0, 1)));
		writtenBy(fleet, EXIT);
		exit.refused(stream, EntryRefused.of(own.get(0), Admission.FULL));
		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(true));
		exit.resend(fleet.chain(), ChainFleet.writer(1), Set.of());

		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(false));
		assertThat(writtenBy(fleet, EXIT).get(successor.getKey()), equalTo(List.of(new ChainStem(own.subList(1, 3)))));
	}

	private record Reach(long posted, long flooded) {
		/* How many entries of the exit went out, and how many of them a member flooded */
	}

	/* A group of the given size in which gn-1 writes the given number of entries for each of the writers, a few of them
	   in each of several periods of the stem, avoiding what the function gives for a writer. Nothing ticks, so no
	   embargo fires, and every packet is handed over before the next entry goes out. */
	private static Reach stemThrough(final ChainFleet fleet, final int writers,
		final Function<Integer, Set<Channel>> avoid) {

		final List<Entry> posted = new ArrayList<>();

		for (int writer = 0; writer < writers; writer++) {
			if (writer % 5 == 0) {
				fleet.clock.addAndGet(StemRouter.PERIOD_MILLIS);
			}

			for (final Entry entry : ChainFleet.entries(ChainFleet.key(100 + writer), fleet.chain(), 2,
				fleet.clock.get())) {

				assertThat(fleet.host(EXIT).postStem(fleet.chain(), entry, avoid.apply(writer)),
					is(in(PASSED_ON.values())));
				posted.add(entry);
				fleet.deliver();
			}
		}

		final Map<Entry, String> firstFlooder = new LinkedHashMap<>();

		fleet.deliveries.stream().filter(delivery -> delivery.packet() instanceof ChainEntry)
			.forEach(delivery -> ((ChainEntry) delivery.packet()).getEntries()
				.forEach(entry -> firstFlooder.putIfAbsent(entry, delivery.from())));

		assertThat(posted.stream().map(firstFlooder::get).filter(EXIT::equals).toList(), is(empty()));
		return new Reach(posted.size(), posted.stream().filter(firstFlooder::containsKey).count());
	}

	/* Whatever the modes and successors of the members, the exit is never the first to flood an entry of its own while
	   no embargo fired. Most entries reach a flood that way; those that went round the stem wait for an embargo. */
	@Example
	public void theExitNeverFloodsItsEntryFirst() {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3), ChainFleet::seeded);
		final Reach reach = stemThrough(fleet, 40, writer -> Set.of());

		assertThat(reach.flooded() * 2, is(greaterThanOrEqualTo(reach.posted())));
	}

	@Example
	public void theExitNeverFloodsItsEntryFirstInAGroupOfFour() {
		final ChainFleet fleet = group(ChainFleet::seeded);
		final Reach reach = stemThrough(fleet, 40, writer -> Set.of());

		assertThat(reach.flooded() * 2, is(greaterThanOrEqualTo(reach.posted())));
	}

	/* A client attached through gn-0 alone, whose entries the exit first hears from there: the stems of the exit's
	   sessions with that client never go to gn-0 */
	@Example
	public void stemsNeverGoToTheMemberAClientAttachedThrough() {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3), ChainFleet::seeded);
		final EmbeddedChannel client = fleet.client(RELAY, 1);

		fleet.host(RELAY).receive(client, new ChainEntry(entries(fleet, 1, 1)));
		fleet.deliver();

		final Optional<Channel> attachMember = hosted(fleet, EXIT).firstFrom(ChainFleet.writer(1));

		assertThat(attachMember, is(Optional.of(fleet.stream(EXIT, RELAY))));
		stemThrough(fleet, 40, writer -> Set.of(attachMember.orElseThrow()));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals(EXIT)
			&& delivery.to().equals(RELAY)).map(ChainFleet.Delivery::packet).toList(),
			everyItem(is(instanceOf(ChainEntry.class))));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals(EXIT)
			&& delivery.packet() instanceof ChainStem).count(), is(greaterThanOrEqualTo(40L)));
	}
}
