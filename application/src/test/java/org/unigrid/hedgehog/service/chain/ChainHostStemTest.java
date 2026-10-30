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
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
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
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
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

		for (final String peer : fleet.activeIds().stream().filter(id -> !id.equals(member)).toList()) {
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

	/* A relay that is passed an entry it passed on before, by a third member, floods it at once, which ends the round:
	   a relay is never the author, so its flood tells nothing of the exit */
	@Example
	public void floodsAStemThatCameRoundThroughAThirdMemberAtOnce() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 1);

		stem(fleet, RELAY, "gn-1", entries);

		final String successor = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String third = OTHERS.stream().filter(member -> !member.equals("gn-1") && !member.equals(successor))
			.findFirst().orElseThrow();

		stem(fleet, RELAY, third, entries);

		final Map<String, List<Object>> written = writtenBy(fleet, RELAY);

		assertThat(written.values(), everyItem(equalTo(List.of(new ChainEntry(entries)))));
		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(entries));
		assertThat(hosted(fleet, RELAY).getEmbargo().bytes(), is(0L));
	}

	/* The author of an entry that came round leaves it to the shorter timers of the relays that hold it */
	@Example
	public void theAuthorDropsItsOwnStemThatCameRound() {
		final ChainFleet fleet = group(ChainFleet.everyMember(true));
		final List<Entry> own = entries(fleet, 1, 1);

		postStem(fleet, own.get(0));
		writtenBy(fleet, EXIT);

		assertThat(stem(fleet, EXIT, RELAY, own), is(empty()));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(hosted(fleet, EXIT).getChain().entries(), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(0)), is(true));
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

	/* A chain of room for nine entries holds seven, and the relay holds one in its embargo that it will take in once
	   it comes back; half the reserve stays for the entries of its own, so it has no room for another */
	@Example
	public void refusesAStemTheChainHasNoRoomForCountingWhatItRelays() {
		final ChainFleet fleet = group(ChainFleet.smallChains(9), ChainFleet.everyMember(false));
		final List<Entry> entries = entries(fleet, 1, 2);

		fleet.host(RELAY).receive(fleet.stream(RELAY, "gn-2"), new ChainEntry(entries(fleet, 3, 7)));
		assertThat(stem(fleet, RELAY, "gn-1", entries.subList(0, 1)), is(empty()));
		assertThat(stem(fleet, RELAY, "gn-1", entries.subList(1, 2)),
			equalTo(List.of(EntryRefused.of(entries.get(1), Admission.FULL))));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(entries.get(1)), is(false));
	}

	/* What a relay holds for others has a share of its own, a quarter of the capacity, past which it refuses more,
	   and that share is kept free of the entries of this node's own, so a member that fills it makes no entry of a
	   session of the exit on that node FULL while the replica has room for the share besides */
	@Example
	public void relayedEntriesUpToTheirShareLeaveTheOwnEntriesTheirRoom() {
		final ChainFleet fleet = group(ChainFleet.smallChains(9), ChainFleet.everyMember(false));
		final List<Entry> relayed = entries(fleet, 2, 3);
		final List<Entry> own = entries(fleet, 1, 7);

		assertThat(stem(fleet, EXIT, RELAY, relayed.subList(0, 2)), is(empty()));
		assertThat(stem(fleet, EXIT, RELAY, relayed.subList(2, 3)),
			equalTo(List.of(EntryRefused.of(relayed.get(2), Admission.FULL))));
		own.forEach(entry -> assertThat(postStem(fleet, entry), is(in(PASSED_ON.values()))));
		assertThat(hosted(fleet, EXIT).getEmbargo().relayedBytes(), is(2L * own.get(0).size()));
		assertThat(hosted(fleet, EXIT).getEmbargo().ownBytes(), is(7L * own.get(0).size()));
	}

	/* A chain of room for nine entries keeps two of them for what it relays, so it stems seven of its own; of the
	   reserve beyond its capacity, relayed entries take half at most, so it holds one for others, and what waits in the
	   stem never comes to more than the chain takes */
	@Example
	public void ownEntriesAndTheRelayedShareNeverExceedTheRoom() {
		final ChainFleet fleet = group(ChainFleet.smallChains(9), ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 8);
		final List<Entry> relayed = entries(fleet, 2, 3);
		final ChainLimits limits = ChainFleet.smallChains(9).chainLimits();

		own.subList(0, 7).forEach(entry -> assertThat(postStem(fleet, entry), is(in(PASSED_ON.values()))));
		assertThat(postStem(fleet, own.get(7)), is(Admission.FULL));
		assertThat(stem(fleet, EXIT, RELAY, relayed), equalTo(List.of(EntryRefused.of(relayed.get(1),
			Admission.FULL), EntryRefused.of(relayed.get(2), Admission.FULL))));
		assertThat(hosted(fleet, EXIT).getEmbargo().bytes(), is(limits.capacityBytes()));
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

	/* The replica of the exit has room for the entries its chain holds but one, kept for what it relays. The stem
	   holds them, the first as taken and the others as held past it, until they come back, so the next waits. */
	@Example
	public void stemsNoEntryTheReplicaWouldHaveNoRoomFor() {
		final ProxySettings settings = ChainFleet.smallChains(5);
		final ChainFleet fleet = group(settings, ChainFleet.everyMember(false));
		final int room = ChainFleet.entriesHeld(settings) - 1;
		final List<Entry> own = entries(fleet, 1, room + 1);

		assertThat(postStem(fleet, own.get(0)), is(Admission.ACCEPTED));
		own.subList(1, room).forEach(entry -> assertThat(postStem(fleet, entry), is(Admission.AHEAD)));
		writtenBy(fleet, EXIT);
		assertThat(postStem(fleet, own.get(room)), is(Admission.FULL));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(room)), is(false));
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

	/* The members a member drew as its successors for the period */
	private static Set<String> drawnBy(final ChainFleet fleet, final String member) {
		final List<Channel> drawn = fleet.host(member).stemSuccessors(fleet.chain());

		return fleet.activeIds().stream().filter(peer -> drawn.contains(fleet.stream(member, peer)))
			.collect(Collectors.toSet());
	}

	/* What a member refused is stemmed again when the refusal comes, from the refused entry on and as far as it is not
	   back yet, to the other successor drawn for the period and never to the member that refused it. What came back
	   already is left to reconcile, and the writer goes on at the next tick. */
	@Example
	public void stemsWhatItStillHoldsToTheOtherSuccessorWhenTheRefusalComes() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final ChainHost exit = fleet.host(EXIT);

		own.forEach(entry -> postStem(fleet, entry));

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();

		exit.receive(fleet.stream(EXIT, RELAY.equals(successor) ? "gn-2" : RELAY), new ChainEntry(own.subList(0, 1)));
		writtenBy(fleet, EXIT);
		exit.refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		final Map<String, List<Object>> again = writtenBy(fleet, EXIT);
		final Map.Entry<String, List<Object>> other = stemmedTo(again);

		assertThat(again.get(successor), is(empty()));
		assertThat(other.getValue(), equalTo(List.of(new ChainStem(own.subList(1, 3)))));
		assertThat(Set.of(successor, other.getKey()), is(drawnBy(fleet, EXIT)));
		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(true));

		exit.resume(fleet.chain(), ChainFleet.writer(1));

		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(false));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
	}

	/* The exit posts two entries of its own and a relay is passed two of another writer, by a member it did not draw;
	   the successor each went to refuses the first. Answers the other successor each stemmed them to. */
	private static Map<String, String> refuseOnce(final ChainFleet fleet, final List<Entry> own,
		final List<Entry> relayed) {

		final String sender = fleet.activeIds().stream().filter(id -> !id.equals(RELAY)
			&& !drawnBy(fleet, RELAY).contains(id)).findFirst().orElseThrow();

		own.forEach(entry -> postStem(fleet, entry));
		stem(fleet, RELAY, sender, relayed);

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		fleet.host(EXIT).refused(fleet.stream(EXIT, fromExit), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, fromRelay), EntryRefused.of(relayed.get(0), Admission.FULL));

		final Map.Entry<String, List<Object>> exitAgain = stemmedTo(writtenBy(fleet, EXIT));
		final Map.Entry<String, List<Object>> relayAgain = stemmedTo(writtenBy(fleet, RELAY));

		assertThat(exitAgain.getValue(), equalTo(List.of(new ChainStem(own))));
		assertThat(relayAgain.getValue(), equalTo(List.of(new ChainStem(relayed))));
		assertThat(Set.of(fromExit, exitAgain.getKey()), is(drawnBy(fleet, EXIT)));
		assertThat(Set.of(fromRelay, relayAgain.getKey()), is(drawnBy(fleet, RELAY)));
		return Map.of(EXIT, exitAgain.getKey(), RELAY, relayAgain.getKey());
	}

	/* An author and a relay that each get one refusal both stem what they hold to their other drawn successor at once,
	   so nothing tells the one from the other */
	@Example
	public void aRelayAndAnAuthorRestemARefusedEntryAlike() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));

		refuseOnce(fleet, entries(fleet, 1, 2), entries(fleet, 2, 2));
	}

	/* A second refusal for the same writer in the period moves its stem no further, at the author and at a relay */
	@Example
	public void aSecondRefusalOfAWriterReroutesNoMore() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 3);
		final Map<String, String> again = refuseOnce(fleet, own.subList(0, 2), relayed.subList(0, 2));

		fleet.host(EXIT).refused(fleet.stream(EXIT, again.get(EXIT)), EntryRefused.of(own.get(1), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, again.get(RELAY)), EntryRefused.of(relayed.get(1),
			Admission.FULL));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));

		postStem(fleet, own.get(2));
		stem(fleet, RELAY, "gn-1".equals(again.get(RELAY)) ? "gn-2" : "gn-1", relayed.subList(2, 3));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(again.get(EXIT)));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(again.get(RELAY)));
	}

	/* Once both drawn successors refused, no member outside the draw of the period gets anything of the writer */
	@Example
	public void noMemberOutsideTheDrawGetsAWriterBothItsSuccessorsRefused() {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3),
			ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 4);
		final Set<String> drawn = drawnBy(fleet, EXIT);
		final Set<String> stemmed = new HashSet<>();

		postStem(fleet, own.get(0));

		for (final Entry refused : own.subList(0, 2)) {
			final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();

			stemmed.add(successor);
			fleet.host(EXIT).refused(fleet.stream(EXIT, successor), EntryRefused.of(refused, Admission.FULL));
			postStem(fleet, own.get(own.indexOf(refused) + 1));
		}

		own.subList(3, 4).forEach(entry -> postStem(fleet, entry));
		writtenBy(fleet, EXIT).forEach((member, packets) -> stemmed.addAll(packets.isEmpty() ? Set.of()
			: Set.of(member)));

		assertThat(drawn, hasSize(StemRouter.SUCCESSORS));
		assertThat(drawn.containsAll(stemmed), is(true));
	}

	/* The member that refused an entry of a writer for want of room gets nothing more of that writer for the rest of
	   the period, whether its predecessor wrote the entry or relayed it */
	@Example
	public void theMemberThatRefusedGetsNothingMoreOfTheWriterForThePeriod() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 4);
		final List<Entry> relayed = entries(fleet, 2, 4);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-1", relayed.subList(0, 1));

		final String refusedExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String refusedRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		fleet.host(EXIT).refused(fleet.stream(EXIT, refusedExit), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, refusedRelay), EntryRefused.of(relayed.get(0),
			Admission.FULL));
		fleet.host(EXIT).resume(fleet.chain(), ChainFleet.writer(1));
		own.subList(1, 4).forEach(entry -> postStem(fleet, entry));
		stem(fleet, RELAY, "gn-1", relayed.subList(1, 4));

		assertThat(writtenBy(fleet, EXIT).get(refusedExit), is(empty()));
		assertThat(writtenBy(fleet, RELAY).get(refusedRelay), is(empty()));
	}

	/* Without a successor besides the member that refused, the refused entry is left to the embargo */
	@Example
	public void leavesARefusedEntryToTheEmbargoWithoutAnotherSuccessor() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final Set<Channel> avoid = Set.of(fleet.stream(EXIT, RELAY), fleet.stream(EXIT, "gn-2"));

		fleet.host(EXIT).postStem(fleet.chain(), own.get(0), avoid);
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is("gn-3"));

		fleet.host(EXIT).refused(fleet.stream(EXIT, "gn-3"), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(EXIT).resume(fleet.chain(), ChainFleet.writer(1));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(0)), is(true));
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

	/* A client attached through gn-0 and gn-3, whose entries the exit first hears from those two: the stems of the
	   exit's sessions with that client go to neither */
	@Example
	public void stemsNeverGoToTheMembersAClientAttachedThrough() {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3), ChainFleet::seeded);
		final Map<String, EmbeddedChannel> attachments = Map.of(RELAY, fleet.client(RELAY, 1), "gn-3",
			fleet.client("gn-3", 1));
		final List<Entry> client = entries(fleet, 1, 1);

		attachments.forEach((member, stream) -> fleet.host(member).receive(stream, new ChainEntry(client)));
		fleet.deliver();

		final Set<Channel> attachMembers = hosted(fleet, EXIT).firstFlooders(ChainFleet.writer(1));

		assertThat(attachMembers, is(Set.of(fleet.stream(EXIT, RELAY), fleet.stream(EXIT, "gn-3"))));
		stemThrough(fleet, 40, writer -> attachMembers);
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals(EXIT)
			&& attachments.containsKey(delivery.to())).map(ChainFleet.Delivery::packet).toList(),
			everyItem(is(instanceOf(ChainEntry.class))));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals(EXIT)
			&& delivery.packet() instanceof ChainStem).count(), is(greaterThanOrEqualTo(40L)));
	}

	/* A successor whose stream takes no writes is passed over by the author and the relay alike, as a flood passes it
	   over, so stems do not pile up behind a member that stopped reading: the embargo floods what it did not take */
	@Example
	public void stemsNothingToASuccessorThatTakesNoWrites() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final List<Entry> relayed = entries(fleet, 2, 1);

		others(EXIT).forEach(member -> ChainFleet.unwritable(fleet.stream(EXIT, member)));
		OTHERS.forEach(member -> ChainFleet.unwritable(fleet.stream(RELAY, member)));

		assertThat(postStem(fleet, own.get(0)), is(Admission.ACCEPTED));
		assertThat(stem(fleet, RELAY, "gn-1", relayed), is(empty()));

		others(EXIT).forEach(member -> assertThat(fleet.stream(EXIT, member).outboundMessages(), is(empty())));
		OTHERS.forEach(member -> assertThat(fleet.stream(RELAY, member).outboundMessages(), is(empty())));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(0)), is(true));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(0)), is(true));
	}

	/* An honest member never passes an entry back to the one it came from, so an entry that comes straight back from
	   the member it was passed to gets the answer of the embargo, at the author and at a relay alike, and nothing
	   tells the one from the other */
	@Example
	public void aDirectBounceGetsTheSameAnswerFromAnAuthorAndARelay() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final List<Entry> relayed = entries(fleet, 2, 1);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-1", relayed);

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		assertThat(stem(fleet, EXIT, fromExit, own), is(empty()));
		assertThat(stem(fleet, RELAY, fromRelay, relayed), is(empty()));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(0)), is(true));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(0)), is(true));
	}

	/* A relay is passed two entries of a writer, by a member it did not draw and by its other drawn successor, and
	   passes both to the same successor, which refuses the first: the other drawn successor sent it the second, so the
	   relay sends neither there, as an honest relay never passes an entry back to the member it got it from */
	@Example
	public void aRelayNeverStemsARefusedEntryBackToTheMemberItCameFrom() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> relayed = entries(fleet, 2, 2);
		final Set<String> drawn = drawnBy(fleet, RELAY);
		final String outsider = fleet.activeIds().stream().filter(id -> !id.equals(RELAY) && !drawn.contains(id))
			.findFirst().orElseThrow();

		stem(fleet, RELAY, outsider, relayed.subList(0, 1));

		final String successor = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String other = drawn.stream().filter(id -> !id.equals(successor)).findFirst().orElseThrow();

		stem(fleet, RELAY, other, relayed.subList(1, 2));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(successor));
		fleet.host(RELAY).refused(fleet.stream(RELAY, successor), EntryRefused.of(relayed.get(0), Admission.FULL));

		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		relayed.forEach(entry -> assertThat(hosted(fleet, RELAY).getEmbargo().holds(entry), is(true)));
	}

	/* A refusal moves on only what went to the member that refused, and not what the embargo held without passing it
	   there */
	@Example
	public void stemsAgainOnlyWhatWentToTheMemberThatRefused() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 2);

		postStem(fleet, own.get(0));

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();

		others(EXIT).forEach(member -> ChainFleet.unwritable(fleet.stream(EXIT, member)));
		postStem(fleet, own.get(1));
		others(EXIT).forEach(member -> fleet.stream(EXIT, member).flush());
		writtenBy(fleet, EXIT);
		fleet.host(EXIT).refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(1)), is(true));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getValue(), equalTo(List.of(new ChainStem(own.subList(0, 1)))));
	}

	/* What an exit avoids grows once the second member its client attached through shows up, so a refusal moves the
	   writer on with what it avoids now: never to a member it came to avoid after it was routed */
	@Example
	public void aRefusalNeverStemsToAMemberTheWriterCameToAvoid() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final AtomicReference<Set<Channel>> avoid = new AtomicReference<>(Set.of());

		fleet.host(EXIT).postStem(fleet.chain(), own.get(0), avoid::get);

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = drawnBy(fleet, EXIT).stream().filter(id -> !id.equals(successor)).findFirst()
			.orElseThrow();

		avoid.set(Set.of(fleet.stream(EXIT, other)));
		fleet.host(EXIT).refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(0)), is(true));
	}

	/* What a relay holds for others never passes its share, whether the entries come in one packet or in packets of
	   several members at once: each is weighed and held in one step */
	@Example
	public void entriesOfOnePacketOrOfConcurrentStemsNeverPassTheRelayedShare() throws Exception {
		final ChainFleet fleet = group(ChainFleet.smallChains(9), ChainFleet.everyMember(false));
		final long share = 2L * ChainFleet.smallChains(9).chainLimits().maxEntryBytes();
		final List<Entry> packet = entries(fleet, 1, 3);

		assertThat(stem(fleet, RELAY, "gn-1", packet), equalTo(List.of(EntryRefused.of(packet.get(2),
			Admission.FULL))));
		assertThat(hosted(fleet, RELAY).getEmbargo().relayedBytes(), is(share));

		final ChainFleet other = group(ChainFleet.smallChains(9), ChainFleet.everyMember(false));
		final ExecutorService threads = Executors.newFixedThreadPool(OTHERS.size());
		final CountDownLatch start = new CountDownLatch(1);
		final List<Future<?>> stems = new ArrayList<>();

		try {
			for (int member = 0; member < OTHERS.size(); member++) {
				final String from = OTHERS.get(member);
				final List<Entry> own = entries(other, 10 + member, 4);

				stems.add(threads.submit(() -> {
					start.await();
					own.forEach(entry -> stem(other, RELAY, from, List.of(entry)));
					return null;
				}));
			}

			start.countDown();

			for (final Future<?> done : stems) {
				done.get(30, TimeUnit.SECONDS);
			}
		} finally {
			threads.shutdownNow();
		}

		assertThat(hosted(other, RELAY).getEmbargo().relayedBytes(), is(lessThanOrEqualTo(share)));
	}

	/* A diffuser weighs the entries of one packet against what the chain will hold once the earlier ones of the packet
	   are in, so the sender hears of the one it had no room for */
	@Example
	public void aDiffuserCountsTheEntriesOfAPacketAsItGoes() {
		final ProxySettings settings = ChainFleet.smallChains(5);
		final ChainFleet fleet = group(settings, ChainFleet.everyMember(true));
		final int held = ChainFleet.entriesHeld(settings);
		final List<Entry> packet = entries(fleet, 1, held + 1);

		assertThat(stem(fleet, RELAY, "gn-1", packet), equalTo(List.of(EntryRefused.of(packet.get(held),
			Admission.FULL))));
		assertThat(hosted(fleet, RELAY).getChain().entries(), equalTo(packet.subList(0, held)));
	}

	/* A host given one generator draws the stem from another seeded by it, so hosts given different generators do not
	   draw the same modes and successors, and hosts given equal ones draw alike */
	@Example
	public void drawsTheStemFromAGeneratorSeededByTheOneItIsGiven() {
		final List<Long> first = ChainHost.Draws.of(new Random(1)).stem().longs(8).boxed().toList();

		assertThat(ChainHost.Draws.of(new Random(1)).stem().longs(8).boxed().toList(), equalTo(first));
		assertThat(ChainHost.Draws.of(new Random(2)).stem().longs(8).boxed().toList(), is(not(equalTo(first))));
	}

	/* A successor that refuses an entry and then passes it straight back gets the same answer from an author and a
	   relay: each passed the entry to it before it went on to the other successor, so each leaves it to the timer */
	@Example
	public void aRefusalThenABounceGetsTheSameAnswerFromAnAuthorAndARelay() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final List<Entry> relayed = entries(fleet, 2, 1);
		final String outsider = fleet.activeIds().stream().filter(id -> !id.equals(RELAY)
			&& !drawnBy(fleet, RELAY).contains(id)).findFirst().orElseThrow();

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, outsider, relayed);

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		fleet.host(EXIT).refused(fleet.stream(EXIT, fromExit), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, fromRelay), EntryRefused.of(relayed.get(0), Admission.FULL));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(not(fromExit)));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(not(fromRelay)));

		assertThat(stem(fleet, EXIT, fromExit, own), is(empty()));
		assertThat(stem(fleet, RELAY, fromRelay, relayed), is(empty()));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(0)), is(true));
	}

	/* A refusal of an entry that the refusing member was never passed through the stem, as one of a copy of a flood or
	   one sent unasked, neither bars that member nor spends the move of the period */
	@Example
	public void aRefusalOfWhatWentElsewhereMovesNothing() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 2);
		final ChainHost exit = fleet.host(EXIT);

		postStem(fleet, own.get(0));

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = drawnBy(fleet, EXIT).stream().filter(id -> !id.equals(successor)).findFirst()
			.orElseThrow();

		exit.receive(fleet.stream(EXIT, other), new ChainEntry(own.subList(0, 1)));
		writtenBy(fleet, EXIT);
		exit.refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));
		postStem(fleet, own.get(1));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(successor));

		exit.refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(1), Admission.FULL));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(other));
	}

	/* A chain of room for nine entries, in eight of capacity and one of reserve, holds seven, so two are left: what
	   the exit relays and what it stems of its own never come to more, whichever comes first. Relayed entries take at
	   most half the reserve, which the entries of its own keep free for them as long as relayed ones could take it. */
	@Example
	public void ownAndRelayedEntriesNeverExceedTheRoomInTheReserveWhicheverComesFirst() {
		final ProxySettings settings = ChainFleet.smallChains(9);
		final ChainLimits limits = settings.chainLimits();

		for (final boolean relayedFirst : List.of(true, false)) {
			final ChainFleet fleet = group(settings, ChainFleet.everyMember(false));
			final List<Entry> relayed = entries(fleet, 2, 2);
			final Entry own = entries(fleet, 1, 1).get(0);

			fleet.host(EXIT).receive(fleet.stream(EXIT, "gn-2"), new ChainEntry(entries(fleet, 3, 7)));

			final long room = limits.capacityBytes() + limits.reserveBytes()
				- hosted(fleet, EXIT).getChain().bytes();

			if (relayedFirst) {
				stem(fleet, EXIT, RELAY, relayed);
				postStem(fleet, own);
			} else {
				postStem(fleet, own);
				stem(fleet, EXIT, RELAY, relayed);
			}

			assertThat(room, is(2L * own.size()));
			assertThat(hosted(fleet, EXIT).getEmbargo().bytes(), is(lessThanOrEqualTo(room)));
			assertThat(hosted(fleet, EXIT).getEmbargo().relayedBytes(), is((long) own.size()));
		}
	}
}
