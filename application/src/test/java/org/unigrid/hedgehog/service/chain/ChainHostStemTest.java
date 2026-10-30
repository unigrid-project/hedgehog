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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
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
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
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
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostStemTest {
	private static final long NOW = ChainFleet.NOW;
	private static final String RELAY = "gn-0";
	private static final List<String> OTHERS = List.of("gn-1", "gn-2", "gn-3");
	private static final int MAX_ROUNDS = 25;
	private static final long MINUTE = 60_000;

	/* The members a writer may be stemmed to in its life: the two drawn first and those drawn in their place */
	private static final int SUCCESSORS_AND_REPLACEMENTS = StemRouter.SUCCESSORS + StemRouter.REPLACEMENTS;

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

	/* The member an entry came from may send it again, which is no round: the relay drops it without a flood and keeps
	   the writer on its route, as an author does, which has no member its own entries came from */
	@Example
	public void anEntrySentAgainByTheMemberItCameFromIsNoRound() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> relayed = entries(fleet, 2, 2);

		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String successor = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		assertThat(stem(fleet, RELAY, "gn-3", relayed.subList(0, 1)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(0)), is(true));

		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(successor));
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

	/* The other successor a member drew for a writer, besides the one it stemmed to */
	private static String otherThan(final ChainFleet fleet, final String member, final int writer,
		final String successor) {

		return drawnBy(fleet, member, writer).stream().filter(id -> !id.equals(successor)).findFirst().orElseThrow();
	}

	/* An entry that comes round to a relay from a member it was not passed to is flooded at once, and one that comes
	   round to its author is left to the timers of the relays, but both move the writer to its other successor for
	   the entries that follow, once for its life, so the successor sees an author and a relay move alike */
	@Example
	public void aLoopMovesTheWriterOnceAtTheAuthorAndAtARelayAlike() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 3);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String exitMovesTo = otherThan(fleet, EXIT, 1, fromExit);
		final String relayMovesTo = otherThan(fleet, RELAY, 2, fromRelay);

		assertThat(stem(fleet, EXIT, exitMovesTo, own.subList(0, 1)), is(empty()));
		assertThat(stem(fleet, RELAY, relayMovesTo, relayed.subList(0, 1)), is(empty()));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(writtenBy(fleet, RELAY).values(), everyItem(equalTo(List.of(new ChainEntry(relayed.subList(0, 1))))));

		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(exitMovesTo));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(relayMovesTo));

		stem(fleet, EXIT, fromExit, own.subList(1, 2));
		stem(fleet, RELAY, fromRelay, relayed.subList(1, 2));
		writtenBy(fleet, EXIT);
		writtenBy(fleet, RELAY);
		postStem(fleet, own.get(2));
		stem(fleet, RELAY, "gn-3", relayed.subList(2, 3));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(exitMovesTo));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(relayMovesTo));
	}

	/* A member forgets what it drew for a writer once the chain forgets the writer, whose feed ended before the
	   retention window, so the draws it holds never outgrow the writers the chain holds */
	@Example
	public void forgetsTheDrawOfAWriterTheChainForgets() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final Entry last = new FeedWriter(ChainFleet.key(2), fleet.chain()).writeLast(NOW, ChainFleet.TAG,
			ChainFleet.padded(new byte[1], ProxySettings.DEFAULTS.chainLimits().maxEntryBytes()));
		final List<Entry> going = entries(fleet, 3, 1);
		final StemRouter router = hosted(fleet, RELAY).getRouter();

		stem(fleet, RELAY, "gn-1", List.of(last));
		stem(fleet, RELAY, "gn-1", going);
		fleet.host(RELAY).receive(fleet.stream(RELAY, "gn-2"), new ChainEntry(List.of(last, going.get(0))));

		assertThat(router.draws(), is(2));
		fleet.clock.addAndGet(31_000);
		fleet.host(RELAY).maintain();

		assertThat(router.draws(), is(1));
		assertThat(router.drawn(ChainFleet.writer(2)), is(empty()));
		assertThat(router.drawn(ChainFleet.writer(3)), hasSize(StemRouter.SUCCESSORS));
	}

	/* A group of eight in which every member relays, so that a successor that went away has members to be replaced by */
	private static ChainFleet groupOfEight() {
		return ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3), ChainFleet.everyMember(false));
	}

	/* When the route of a writer goes away, its other drawn successor takes the route, at the author and at a relay
	   alike, and nothing is drawn in its place while that one is there */
	@Example
	public void theSurvivingSuccessorTakesTheRouteWhenItsPartnerWentAway() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> relayed = entries(fleet, 2, 2);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String otherOfExit = otherThan(fleet, EXIT, 1, fromExit);
		final String otherOfRelay = otherThan(fleet, RELAY, 2, fromRelay);

		fleet.stream(EXIT, fromExit).close();
		fleet.stream(RELAY, fromRelay).close();
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(otherOfExit));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(otherOfRelay));
		assertThat(drawnBy(fleet, EXIT, 1), is(Set.of(otherOfExit)));
		assertThat(drawnBy(fleet, RELAY, 2), is(Set.of(otherOfRelay)));
	}

	/* A successor that went away is never drawn for the writer again, though it comes back on a new stream: in a group
	   of three, both successors of the exit and the one of a relay reconnect, and no member is left to draw, at the
	   author and at a relay alike */
	@Example
	public void aSuccessorThatKeepsReconnectingIsNeverDrawnAgain() {
		final ChainFleet fleet = ChainFleet.group(3, ChainFleet.settings(3, 4, 16, 30, 3),
			ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 2);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, EXIT, relayed.subList(0, 1));

		final String route = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = otherThan(fleet, EXIT, 1, route);
		final String relayRoute = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		fleet.reconnect(EXIT, route);
		fleet.reconnect(RELAY, relayRoute);
		fleet.refreshAll();
		postStem(fleet, own.get(1));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(other));

		fleet.reconnect(EXIT, other);
		fleet.refreshAll();
		postStem(fleet, own.get(2));
		stem(fleet, RELAY, EXIT, relayed.subList(1, 2));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(2)), is(true));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(1)), is(true));
	}

	/* A route the writer of a session comes to avoid, as the second member its client attached through, leaves the
	   writer to its other drawn successor, and only once it avoids both is a member outside the draw drawn */
	@Example
	public void takesTheOtherSuccessorWhenTheExitComesToAvoidItsRoute() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 3);
		final AtomicReference<Set<Channel>> avoid = new AtomicReference<>(Set.of());

		fleet.host(EXIT).postStem(fleet.chain(), own.get(0), avoid::get);

		final String route = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = otherThan(fleet, EXIT, 1, route);

		avoid.set(Set.of(fleet.stream(EXIT, route)));
		fleet.host(EXIT).postStem(fleet.chain(), own.get(1), avoid::get);
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(other));

		avoid.set(Set.of(fleet.stream(EXIT, route), fleet.stream(EXIT, other)));
		fleet.host(EXIT).postStem(fleet.chain(), own.get(2), avoid::get);
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(not(in(Set.of(route, other)))));
	}

	/* A successor that went away leaves the writer to the other, and when that one refuses, a member is drawn in the
	   place of the one that went, within the replacements of the writer, and takes what was refused: a refusal after a
	   departure leaves neither the author nor a relay without a successor */
	@Example
	public void aRefusalAfterADepartureDrawsAReplacement() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 1);
		final List<Entry> relayed = entries(fleet, 2, 1);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed);

		final String exitRoute = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String relayRoute = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final Set<String> exitDrew = drawnBy(fleet, EXIT, 1);
		final Set<String> relayDrew = drawnBy(fleet, RELAY, 2);

		fleet.stream(EXIT, otherThan(fleet, EXIT, 1, exitRoute)).close();
		fleet.stream(RELAY, otherThan(fleet, RELAY, 2, relayRoute)).close();
		fleet.host(EXIT).refused(fleet.stream(EXIT, exitRoute), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, relayRoute), EntryRefused.of(relayed.get(0), Admission.FULL));

		final Map.Entry<String, List<Object>> exitAgain = stemmedTo(writtenBy(fleet, EXIT));
		final Map.Entry<String, List<Object>> relayAgain = stemmedTo(writtenBy(fleet, RELAY));

		assertThat(exitAgain.getKey(), is(not(in(exitDrew))));
		assertThat(relayAgain.getKey(), is(not(in(relayDrew))));
		assertThat(exitAgain.getValue(), equalTo(List.of(new ChainStem(own))));
		assertThat(relayAgain.getValue(), equalTo(List.of(new ChainStem(relayed))));
		assertThat(fleet.host(EXIT).isStranded(fleet.chain(), ChainFleet.writer(1)), is(false));
	}

	/* The same when the other successor of a writer of the exit comes to be avoided rather than going away */
	@Example
	public void aRefusalAfterTheExitComesToAvoidItsOtherSuccessorDrawsAReplacement() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 1);
		final AtomicReference<Set<Channel>> avoid = new AtomicReference<>(Set.of());

		fleet.host(EXIT).postStem(fleet.chain(), own.get(0), avoid::get);

		final String route = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = otherThan(fleet, EXIT, 1, route);

		avoid.set(Set.of(fleet.stream(EXIT, other)));
		fleet.host(EXIT).refused(fleet.stream(EXIT, route), EntryRefused.of(own.get(0), Admission.FULL));

		final Map.Entry<String, List<Object>> again = stemmedTo(writtenBy(fleet, EXIT));

		assertThat(again.getKey(), is(not(in(Set.of(route, other)))));
		assertThat(again.getValue(), equalTo(List.of(new ChainStem(own))));
		assertThat(fleet.host(EXIT).isStranded(fleet.chain(), ChainFleet.writer(1)), is(false));
	}

	/* The one move of a writer bars the member it moved off, but a successor that went away after it is still replaced,
	   by a member outside the draw and never by the barred one, at the author and at a relay alike */
	@Example
	public void replacesASuccessorThatWentAwayAfterARefusalMovedTheWriter() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> relayed = entries(fleet, 2, 2);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final Set<String> exitDrew = drawnBy(fleet, EXIT, 1);
		final Set<String> relayDrew = drawnBy(fleet, RELAY, 2);

		fleet.host(EXIT).refused(fleet.stream(EXIT, stemmedTo(writtenBy(fleet, EXIT)).getKey()),
			EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, stemmedTo(writtenBy(fleet, RELAY)).getKey()),
			EntryRefused.of(relayed.get(0), Admission.FULL));
		fleet.stream(EXIT, stemmedTo(writtenBy(fleet, EXIT)).getKey()).close();
		fleet.stream(RELAY, stemmedTo(writtenBy(fleet, RELAY)).getKey()).close();
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(not(in(exitDrew))));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(not(in(relayDrew))));
	}

	/* Closes the streams to the successors a member has for a writer, again and again, until none is left, and
	   answers how many times it closed them */
	private static int closeEverySuccessor(final ChainFleet fleet, final String member, final int writer) {
		int closed = 0;

		for (Set<String> left = drawnBy(fleet, member, writer); !left.isEmpty(); left = drawnBy(fleet, member, writer)) {
			left.forEach(peer -> fleet.stream(member, peer).close());
			fleet.host(member).isStranded(fleet.chain(), ChainFleet.writer(writer));
			closed++;
		}

		return closed;
	}

	/* The packets of entries a member wrote that hold the given entry */
	private static List<Object> floodsOf(final Map<String, List<Object>> written, final Entry entry) {
		return all(written).stream().filter(packet -> packet instanceof ChainEntry flood
			&& flood.getEntries().contains(entry)).toList();
	}

	/* Once both successors of a writer of the exit went away and so did the two drawn after them, the writer has no
	   successor left, though members are: the exit says so, and never floods what the writer posts after, which went
	   nowhere */
	@Example
	public void anExitFloodsNothingOfItsOwnThatWentNowhereOnceNoSuccessorIsLeft() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final ChainHost exit = fleet.host(EXIT);

		postStem(fleet, own.get(0));

		assertThat(exit.isStranded(fleet.chain(), ChainFleet.writer(1)), is(false));
		assertThat(closeEverySuccessor(fleet, EXIT, 1), is(1 + StemRouter.REPLACEMENTS));
		assertThat(exit.isStranded(fleet.chain(), ChainFleet.writer(1)), is(true));
		assertThat(exit.canStem(fleet.chain(), Set.of()), is(true));

		writtenBy(fleet, EXIT);
		postStem(fleet, own.get(1));

		for (int second = 0; second < MAX_ROUNDS; second++) {
			fleet.clock.addAndGet(1000);
			exit.maintain();
			assertThat(floodsOf(writtenBy(fleet, EXIT), own.get(1)), is(empty()));
		}

		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(1)), is(false));
	}

	/* A successor that stops reading never makes the exit flood an entry of its own that never left: once such an
	   entry waited out its deadline, the route counts as gone and the entry goes to the other successor, then to one
	   drawn in place of both, and once none is left the writer has no successor and the entry is dropped. A member
	   that stalled is never drawn for the writer again, though it reads again. */
	@Example
	public void aSuccessorThatStopsReadingNeverMakesTheExitFloodItsOwn() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2 + SUCCESSORS_AND_REPLACEMENTS);
		final ChainHost exit = fleet.host(EXIT);
		final Set<String> stalled = new HashSet<>();

		postStem(fleet, own.get(0));

		String route = stemmedTo(writtenBy(fleet, EXIT)).getKey();

		for (final Entry unsent : own.subList(1, own.size())) {
			Optional<String> moved = Optional.empty();
			int rounds = 0;

			stalled.forEach(member -> fleet.stream(EXIT, member).flush());
			ChainFleet.unwritable(fleet.stream(EXIT, route));
			stalled.add(route);
			postStem(fleet, unsent);

			while (moved.isEmpty() && hosted(fleet, EXIT).getEmbargo().holds(unsent) && rounds < MAX_ROUNDS) {
				fleet.clock.addAndGet(1000);
				exit.maintain();

				final Map<String, List<Object>> written = writtenBy(fleet, EXIT);

				assertThat(floodsOf(written, unsent), is(empty()));
				moved = written.entrySet().stream().filter(sent -> sent.getValue()
					.contains(new ChainStem(List.of(unsent)))).map(Map.Entry::getKey).findFirst();
				rounds++;
			}

			assertThat(rounds, is(lessThan(MAX_ROUNDS)));
			moved.ifPresent(next -> assertThat(next, is(not(in(stalled)))));
			route = moved.orElse(route);
		}

		assertThat(stalled, hasSize(SUCCESSORS_AND_REPLACEMENTS));
		assertThat(exit.isStranded(fleet.chain(), ChainFleet.writer(1)), is(true));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(own.size() - 1)), is(false));
	}

	/* Turns the stream between two members unwritable anew, so that it took writes in between */
	private static void stallAgain(final ChainFleet fleet, final String owner, final String peer) {
		fleet.stream(owner, peer).flush();
		ChainFleet.unwritable(fleet.stream(owner, peer));
	}

	/* A route that takes writes now and then gets what waits for it as soon as it does, from an author and a relay
	   alike, and the writer stays on it: a member cannot spend the replacements of a writer by turning unwritable
	   for a moment at each deadline, nor hold up what waits for it that way */
	@Example
	public void aRouteThatTakesWritesNowAndThenGetsWhatWaitsForIt() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> relayed = entries(fleet, 2, 2);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String exitRoute = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String relayRoute = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final Set<String> exitDrew = drawnBy(fleet, EXIT, 1);
		final Set<String> relayDrew = drawnBy(fleet, RELAY, 2);

		ChainFleet.unwritable(fleet.stream(EXIT, exitRoute));
		ChainFleet.unwritable(fleet.stream(RELAY, relayRoute));
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));
		fleet.clock.addAndGet(1000);
		stallAgain(fleet, EXIT, exitRoute);
		stallAgain(fleet, RELAY, relayRoute);

		final Map<String, List<Object>> fromExit = writtenBy(fleet, EXIT);
		final Map<String, List<Object>> fromRelay = writtenBy(fleet, RELAY);

		assertThat(stemmedWith(fromExit, own.get(1)), is(Optional.of(exitRoute)));
		assertThat(stemmedWith(fromRelay, relayed.get(1)), is(Optional.of(relayRoute)));
		assertThat(floodsOf(fromExit, own.get(1)), is(empty()));
		assertThat(floodsOf(fromRelay, relayed.get(1)), is(empty()));
		assertThat(drawnBy(fleet, EXIT, 1), is(exitDrew));
		assertThat(drawnBy(fleet, RELAY, 2), is(relayDrew));
	}

	/* Takes every stem written to the stream and never sends it on, as a member whose stream accepts what it is handed
	   and then stops reading */
	private static void holdStems(final EmbeddedChannel stream) {
		stream.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(final ChannelHandlerContext context, final Object packet, final ChannelPromise promise) {
				if (!(packet instanceof ChainStem)) {
					context.write(packet, promise);
				}
			}
		});
	}

	/* A route that seems to take writes but never gets one out does not stall without a break, so it is moved off
	   once an entry missed MISSED_DEADLINES deadlines on it, at the author and at a relay alike, and the entry goes
	   late to the other successor without ever being flooded before */
	@Example
	public void aRouteThatNeverGetsAWriteOutIsMovedOffAfterTwoMissedDeadlines() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> relayed = entries(fleet, 2, 2);
		Optional<String> exitMoved = Optional.empty();
		Optional<String> relayMoved = Optional.empty();
		int rounds = 0;

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String exitRoute = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String relayRoute = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		holdStems(fleet.stream(EXIT, exitRoute));
		holdStems(fleet.stream(RELAY, relayRoute));
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		while ((exitMoved.isEmpty() || relayMoved.isEmpty()) && rounds < 3 * MAX_ROUNDS) {
			fleet.clock.addAndGet(1000);
			fleet.host(EXIT).maintain();
			fleet.host(RELAY).maintain();

			final Map<String, List<Object>> fromExit = writtenBy(fleet, EXIT);
			final Map<String, List<Object>> fromRelay = writtenBy(fleet, RELAY);

			assertThat(exitMoved.isEmpty() ? floodsOf(fromExit, own.get(1)) : List.of(), is(empty()));
			assertThat(relayMoved.isEmpty() ? floodsOf(fromRelay, relayed.get(1)) : List.of(), is(empty()));
			exitMoved = exitMoved.or(() -> stemmedWith(fromExit, own.get(1)));
			relayMoved = relayMoved.or(() -> stemmedWith(fromRelay, relayed.get(1)));
			rounds++;
		}

		assertThat(exitMoved, is(Optional.of(otherThan(fleet, EXIT, 1, exitRoute))));
		assertThat(relayMoved, is(Optional.of(otherThan(fleet, RELAY, 2, relayRoute))));
	}

	/* The member that a stem went to first of the given ones, if any */
	private static Optional<String> stemmedWith(final Map<String, List<Object>> written, final Entry entry) {
		return written.entrySet().stream().filter(sent -> sent.getValue().stream().anyMatch(packet -> packet
			instanceof ChainStem stem && stem.getEntries().contains(entry))).map(Map.Entry::getKey).findFirst();
	}

	/* A route that stops reading gets the same from an author and a relay. What it did not take never leaves by flood
	   from either: once it reads again within the wait, it gets that late, and once it took nothing for the whole wait
	   of an entry, the writer moves off it and the entry goes late to the successor that takes its place. Only once it
	   went there and did not come back is it flooded at the deadline, as anything passed on is. */
	@Example
	public void aStalledRouteGetsTheSameFromAnAuthorAndARelay() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 3);
		Optional<String> exitMoved = Optional.empty();
		Optional<String> relayMoved = Optional.empty();
		int rounds = 0;

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String exitRoute = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String relayRoute = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String exitOther = otherThan(fleet, EXIT, 1, exitRoute);
		final String relayOther = otherThan(fleet, RELAY, 2, relayRoute);

		ChainFleet.unwritable(fleet.stream(EXIT, exitRoute));
		ChainFleet.unwritable(fleet.stream(RELAY, relayRoute));
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));

		fleet.stream(EXIT, exitRoute).flush();
		fleet.stream(RELAY, relayRoute).flush();

		final Map<String, List<Object>> exitLate = writtenBy(fleet, EXIT);
		final Map<String, List<Object>> relayLate = writtenBy(fleet, RELAY);

		assertThat(stemmedWith(exitLate, own.get(1)), is(Optional.of(exitRoute)));
		assertThat(stemmedWith(relayLate, relayed.get(1)), is(Optional.of(relayRoute)));
		assertThat(floodsOf(exitLate, own.get(1)), is(empty()));
		assertThat(floodsOf(relayLate, relayed.get(1)), is(empty()));

		ChainFleet.unwritable(fleet.stream(EXIT, exitRoute));
		ChainFleet.unwritable(fleet.stream(RELAY, relayRoute));
		postStem(fleet, own.get(2));
		stem(fleet, RELAY, "gn-3", relayed.subList(2, 3));

		while ((exitMoved.isEmpty() || relayMoved.isEmpty()) && rounds < MAX_ROUNDS) {
			fleet.clock.addAndGet(1000);
			fleet.host(EXIT).maintain();
			fleet.host(RELAY).maintain();

			final Map<String, List<Object>> fromExit = writtenBy(fleet, EXIT);
			final Map<String, List<Object>> fromRelay = writtenBy(fleet, RELAY);

			assertThat(exitMoved.isEmpty() ? floodsOf(fromExit, own.get(2)) : List.of(), is(empty()));
			assertThat(relayMoved.isEmpty() ? floodsOf(fromRelay, relayed.get(2)) : List.of(), is(empty()));
			exitMoved = exitMoved.or(() -> stemmedWith(fromExit, own.get(2)));
			relayMoved = relayMoved.or(() -> stemmedWith(fromRelay, relayed.get(2)));
			rounds++;
		}

		assertThat(exitMoved, is(Optional.of(exitOther)));
		assertThat(relayMoved, is(Optional.of(relayOther)));
	}

	/* A relay in the same place floods what it can pass on to nobody at its timer, which tells nothing of the exit */
	@Example
	public void aRelayWithNoSuccessorLeftFloodsAtItsTimer() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> relayed = entries(fleet, 2, 2);
		int rounds = 0;

		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		assertThat(closeEverySuccessor(fleet, RELAY, 2), is(1 + StemRouter.REPLACEMENTS));

		writtenBy(fleet, RELAY);
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));

		while (!hosted(fleet, RELAY).getChain().entries().contains(relayed.get(1)) && rounds < MAX_ROUNDS) {
			fleet.clock.addAndGet(1000);
			fleet.host(RELAY).maintain();
			rounds++;
		}

		assertThat(rounds, is(lessThan(MAX_ROUNDS)));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(1)), is(false));
		assertThat(floodsOf(writtenBy(fleet, RELAY), relayed.get(1)), is(not(empty())));
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

	/* At the smallest capacity the spork allows, an empty replica keeps the share of what it relays free and still
	   has room for one of the largest entries of the node's own */
	@Property(tries = 20)
	public void anEmptyReplicaTakesALargestEntryOfItsOwnAtTheSmallestCapacity(
		@ForAll @IntRange(min = 1024, max = 1024 * 1024) int largest) {

		final ChainId chainId = new ChainId(new byte[ChainId.SIZE]);
		final ProxySettings smallest = new ProxySettings(8, 3600, 4, 16, 4L * largest, 30, largest, 300, 3);
		final HostedChain hosted = new HostedChain(new Chain(chainId, smallest.chainLimits(), () -> NOW),
			new Epoch(0), 1, new Random(1));
		final Entry entry = new FeedWriter(ChainFleet.key(1), chainId).write(NOW, ChainFleet.TAG,
			new byte[largest - Entry.OVERHEAD]);

		assertThat(entry.size(), is(largest));
		assertThat(hosted.holdOwn(entry, NOW), is(Admission.ACCEPTED));
	}

	/* A writer whose entries are all in the stem has a place on the chain as if they were in it */
	@Example
	public void aWriterInTheStemHoldsAPlace() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final HostedChain hosted = new HostedChain(new Chain(fleet.chain(), ProxySettings.DEFAULTS.chainLimits(),
			fleet.clock::get), new Epoch(0), 1, new Random(1));

		assertThat(hosted.holdOwn(entries(fleet, 1, 1).get(0), NOW), is(Admission.ACCEPTED));
		assertThat(hosted.takesNewWriters(), is(false));
		assertThat(hosted.holdOwn(entries(fleet, 2, 1).get(0), NOW), is(Admission.FULL));
		assertThat(hosted.getEmbargo().holdsAnyOf(ChainFleet.writer(2)), is(false));
	}

	/* The members a member drew as the successors of a writer */
	private static Set<String> drawnBy(final ChainFleet fleet, final String member, final int writer) {
		final List<Channel> drawn = fleet.host(member).stemSuccessors(fleet.chain(), ChainFleet.writer(writer));

		return fleet.activeIds().stream().filter(peer -> drawn.contains(fleet.stream(member, peer)))
			.collect(Collectors.toSet());
	}

	/* What a member refused is stemmed again when the refusal comes, from the refused entry on and as far as it is not
	   back yet, to the other successor drawn for the writer and never to the member that refused it. What came back
	   already is left to reconcile, and the writer goes on at the next tick. */
	@Example
	public void stemsWhatItStillHoldsToTheOtherSuccessorWhenTheRefusalComes() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final ChainHost exit = fleet.host(EXIT);

		own.forEach(entry -> postStem(fleet, entry));

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final Set<String> drawn = drawnBy(fleet, EXIT, 1);

		exit.receive(fleet.stream(EXIT, RELAY.equals(successor) ? "gn-2" : RELAY), new ChainEntry(own.subList(0, 1)));
		writtenBy(fleet, EXIT);
		exit.refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		final Map<String, List<Object>> again = writtenBy(fleet, EXIT);
		final Map.Entry<String, List<Object>> other = stemmedTo(again);

		assertThat(again.get(successor), is(empty()));
		assertThat(other.getValue(), equalTo(List.of(new ChainStem(own.subList(1, 3)))));
		assertThat(Set.of(successor, other.getKey()), is(drawn));
		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(true));

		exit.resume(fleet.chain(), ChainFleet.writer(1));

		assertThat(exit.pauses().isPaused(fleet.chain(), ChainFleet.writer(1)), is(false));
		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
	}

	/* The exit posts two entries of its own, writer 1, and a relay is passed two of writer 2 by gn-3, which it draws
	   no successor of the writer among; the successor each went to refuses the first. Answers the other successor each
	   stemmed them to. */
	private static Map<String, String> refuseOnce(final ChainFleet fleet, final List<Entry> own,
		final List<Entry> relayed) {

		final String sender = "gn-3";

		own.forEach(entry -> postStem(fleet, entry));
		stem(fleet, RELAY, sender, relayed);

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final Set<String> exitDrew = drawnBy(fleet, EXIT, 1);
		final Set<String> relayDrew = drawnBy(fleet, RELAY, 2);

		fleet.host(EXIT).refused(fleet.stream(EXIT, fromExit), EntryRefused.of(own.get(0), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, fromRelay), EntryRefused.of(relayed.get(0), Admission.FULL));

		final Map.Entry<String, List<Object>> exitAgain = stemmedTo(writtenBy(fleet, EXIT));
		final Map.Entry<String, List<Object>> relayAgain = stemmedTo(writtenBy(fleet, RELAY));

		assertThat(exitAgain.getValue(), equalTo(List.of(new ChainStem(own))));
		assertThat(relayAgain.getValue(), equalTo(List.of(new ChainStem(relayed))));
		assertThat(Set.of(fromExit, exitAgain.getKey()), is(exitDrew));
		assertThat(Set.of(fromRelay, relayAgain.getKey()), is(relayDrew));
		return Map.of(EXIT, exitAgain.getKey(), RELAY, relayAgain.getKey());
	}

	/* An author and a relay that each get one refusal both stem what they hold to their other drawn successor at once,
	   so nothing tells the one from the other */
	@Example
	public void aRelayAndAnAuthorRestemARefusedEntryAlike() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));

		refuseOnce(fleet, entries(fleet, 1, 2), entries(fleet, 2, 2));
	}

	/* A second refusal for the same writer moves its stem no further, at the author and at a relay, and the member
	   that refused gets nothing more of it either: the writer has no successor left. The exit floods nothing of its
	   own that went nowhere, and ends the session, while a relay floods what it holds at its timer. */
	@Example
	public void aSecondRefusalOfAWriterLeavesItNoSuccessor() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 3);
		final Map<String, String> again = refuseOnce(fleet, own.subList(0, 2), relayed.subList(0, 2));
		int rounds = 0;

		fleet.host(EXIT).refused(fleet.stream(EXIT, again.get(EXIT)), EntryRefused.of(own.get(1), Admission.FULL));
		fleet.host(RELAY).refused(fleet.stream(RELAY, again.get(RELAY)), EntryRefused.of(relayed.get(1),
			Admission.FULL));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(fleet.host(EXIT).isStranded(fleet.chain(), ChainFleet.writer(1)), is(true));

		postStem(fleet, own.get(2));
		stem(fleet, RELAY, "gn-3", relayed.subList(2, 3));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));

		while ((!hosted(fleet, RELAY).getChain().entries().contains(relayed.get(2))
			|| hosted(fleet, EXIT).getEmbargo().holds(own.get(2))) && rounds < MAX_ROUNDS) {

			fleet.clock.addAndGet(1000);
			fleet.host(EXIT).maintain();
			fleet.host(RELAY).maintain();
			assertThat(floodsOf(writtenBy(fleet, EXIT), own.get(2)), is(empty()));
			rounds++;
		}

		assertThat(rounds, is(lessThan(MAX_ROUNDS)));
		assertThat(floodsOf(writtenBy(fleet, RELAY), relayed.get(2)), is(not(empty())));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(2)), is(false));
	}

	/* Once both drawn successors refused, no member outside the draw of the writer gets anything of it */
	@Example
	public void noMemberOutsideTheDrawGetsAWriterBothItsSuccessorsRefused() {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3),
			ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 4);
		final Set<String> stemmed = new HashSet<>();

		postStem(fleet, own.get(0));

		final Set<String> drawn = drawnBy(fleet, EXIT, 1);

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

	/* The member that refused an entry of a writer for want of room gets nothing more of that writer, whether its
	   predecessor wrote the entry or relayed it */
	@Example
	public void theMemberThatRefusedGetsNothingMoreOfTheWriter() {
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

	/* A group of the given size in which gn-1 writes two entries for each of the writers, a few of them in each of
	   several minutes, avoiding what the function gives for a writer. Nothing ticks, so no embargo fires, and every
	   packet is handed over before the next entry goes out. */
	private static Reach stemThrough(final ChainFleet fleet, final int writers,
		final Function<Integer, Set<Channel>> avoid) {

		final List<Entry> posted = new ArrayList<>();

		for (int writer = 0; writer < writers; writer++) {
			if (writer % 5 == 0) {
				fleet.clock.addAndGet(MINUTE);
			}

			for (final Entry entry : ChainFleet.entries(ChainFleet.key(100 + writer), fleet.chain(), 2,
				fleet.clock.get())) {

				assertThat(fleet.host(EXIT).postStem(fleet.chain(), entry, avoid.apply(writer)),
					is(in(PASSED_ON.values())));
				posted.add(entry);
				fleet.deliver();
			}
		}

		final Map<Entry, String> firstFlooder = firstFlooders(fleet);

		assertThat(posted.stream().map(firstFlooder::get).filter(EXIT::equals).toList(), is(empty()));
		return new Reach(posted.size(), posted.stream().filter(firstFlooder::containsKey).count());
	}

	/* The member that flooded each entry first, as the fleet handed the floods over */
	private static Map<Entry, String> firstFlooders(final ChainFleet fleet) {
		final Map<Entry, String> firstFlooder = new LinkedHashMap<>();

		fleet.deliveries.stream().filter(delivery -> delivery.packet() instanceof ChainEntry)
			.forEach(delivery -> ((ChainEntry) delivery.packet()).getEntries()
				.forEach(entry -> firstFlooder.putIfAbsent(entry, delivery.from())));

		return firstFlooder;
	}

	/* Hosts that each draw the stem from a generator of their own, spread from the seed and their id */
	private static Function<String, RandomGenerator> seeded(final long seed) {
		return id -> new Random(new SplittableRandom(seed + Objects.hashCode(id)).nextLong());
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

	/* An entry that the route of its writer passes to a relay is never passed back there: it waits in the embargo,
	   which floods it in time, and the writer stays on its route, as an author's would, whose entries come from no
	   member */
	@Example
	public void aRelayHoldsWhatItsRouteSentItAndKeepsTheRoute() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> relayed = entries(fleet, 2, 3);

		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String route = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		assertThat(stem(fleet, RELAY, route, relayed.subList(1, 2)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(1)), is(true));

		stem(fleet, RELAY, "gn-3", relayed.subList(2, 3));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(route));
	}

	/* A route that takes no writes for a while is skipped for what comes meanwhile, which waits in the embargo, and the
	   writer stays on it: once it takes writes again the next entry goes there, at the author and at a relay alike */
	@Example
	public void skipsARouteThatTakesNoWritesAndKeepsIt() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 3);
		final List<Entry> relayed = entries(fleet, 2, 3);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final String fromExit = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String fromRelay = stemmedTo(writtenBy(fleet, RELAY)).getKey();

		ChainFleet.unwritable(fleet.stream(EXIT, fromExit));
		ChainFleet.unwritable(fleet.stream(RELAY, fromRelay));
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		assertThat(all(writtenBy(fleet, EXIT)), is(empty()));
		assertThat(all(writtenBy(fleet, RELAY)), is(empty()));
		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(1)), is(true));
		assertThat(hosted(fleet, RELAY).getEmbargo().holds(relayed.get(1)), is(true));

		fleet.stream(EXIT, fromExit).flush();
		fleet.stream(RELAY, fromRelay).flush();
		writtenBy(fleet, EXIT);
		writtenBy(fleet, RELAY);
		postStem(fleet, own.get(2));
		stem(fleet, RELAY, "gn-3", relayed.subList(2, 3));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(fromExit));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(fromRelay));
	}

	/* Fails every stem written to the stream, as when the stream closes before what it was handed went out */
	private static void failStems(final EmbeddedChannel stream) {
		stream.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(final ChannelHandlerContext context, final Object packet, final ChannelPromise promise) {
				if (packet instanceof ChainStem) {
					promise.setFailure(new ClosedChannelException());
				} else {
					context.write(packet, promise);
				}
			}
		});
	}

	/* An entry counts as passed to a successor once its stream took it, so one whose write failed, as when the stream
	   closed before it went out, never left, at the author and at a relay alike, and the exit never floods it before
	   it went to another successor */
	@Example
	public void anEntryWhoseWriteFailedNeverLeft() {
		final ChainFleet fleet = groupOfEight();
		final List<Entry> own = entries(fleet, 1, 2);
		final List<Entry> relayed = entries(fleet, 2, 2);

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));
		failStems(fleet.stream(EXIT, stemmedTo(writtenBy(fleet, EXIT)).getKey()));
		failStems(fleet.stream(RELAY, stemmedTo(writtenBy(fleet, RELAY)).getKey()));
		postStem(fleet, own.get(1));
		stem(fleet, RELAY, "gn-3", relayed.subList(1, 2));

		assertThat(hosted(fleet, EXIT).getEmbargo().wasSent(own.get(1)), is(false));
		assertThat(hosted(fleet, RELAY).getEmbargo().wasSent(relayed.get(1)), is(false));

		Optional<String> moved = Optional.empty();

		for (int second = 0; second < 3 * MAX_ROUNDS && moved.isEmpty(); second++) {
			fleet.clock.addAndGet(1000);
			fleet.host(EXIT).maintain();

			final Map<String, List<Object>> written = writtenBy(fleet, EXIT);

			assertThat(floodsOf(written, own.get(1)), is(empty()));
			moved = stemmedWith(written, own.get(1));
		}

		assertThat(moved.isPresent(), is(true));
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
	   passes both to the same successor, which refuses the first: the writer moves to the other drawn successor, which
	   gets the first again but not the second, which it sent, as an honest relay never passes an entry back to the
	   member it got it from */
	@Example
	public void aRelayNeverStemsARefusedEntryBackToTheMemberItCameFrom() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> relayed = entries(fleet, 2, 2);

		stem(fleet, RELAY, "gn-3", relayed.subList(0, 1));

		final Set<String> drawn = drawnBy(fleet, RELAY, 2);
		final String successor = stemmedTo(writtenBy(fleet, RELAY)).getKey();
		final String other = drawn.stream().filter(id -> !id.equals(successor)).findFirst().orElseThrow();

		stem(fleet, RELAY, other, relayed.subList(1, 2));
		assertThat(stemmedTo(writtenBy(fleet, RELAY)).getKey(), is(successor));
		fleet.host(RELAY).refused(fleet.stream(RELAY, successor), EntryRefused.of(relayed.get(0), Admission.FULL));

		final Map.Entry<String, List<Object>> again = stemmedTo(writtenBy(fleet, RELAY));

		assertThat(again.getKey(), is(other));
		assertThat(again.getValue(), equalTo(List.of(new ChainStem(relayed.subList(0, 1)))));
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
		others(EXIT).stream().filter(member -> !member.equals(successor))
			.forEach(member -> fleet.stream(EXIT, member).flush());
		writtenBy(fleet, EXIT);
		fleet.host(EXIT).refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		assertThat(hosted(fleet, EXIT).getEmbargo().holds(own.get(1)), is(true));
		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getValue(), equalTo(List.of(new ChainStem(own.subList(0, 1)))));
	}

	/* What an exit avoids grows once the second member its client attached through shows up, so a refusal moves the
	   writer on with what it avoids now: never to a member it came to avoid after it was routed, but to the one member
	   of the group left, drawn in the place of the one it came to avoid */
	@Example
	public void aRefusalNeverStemsToAMemberTheWriterCameToAvoid() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 1);
		final AtomicReference<Set<Channel>> avoid = new AtomicReference<>(Set.of());

		fleet.host(EXIT).postStem(fleet.chain(), own.get(0), avoid::get);

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = drawnBy(fleet, EXIT, 1).stream().filter(id -> !id.equals(successor)).findFirst()
			.orElseThrow();

		avoid.set(Set.of(fleet.stream(EXIT, other)));
		fleet.host(EXIT).refused(fleet.stream(EXIT, successor), EntryRefused.of(own.get(0), Admission.FULL));

		assertThat(stemmedTo(writtenBy(fleet, EXIT)).getKey(), is(not(in(Set.of(successor, other)))));
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

		postStem(fleet, own.get(0));
		stem(fleet, RELAY, "gn-3", relayed);

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
	   one sent unasked, neither bars that member nor spends the move of the writer */
	@Example
	public void aRefusalOfWhatWentElsewhereMovesNothing() {
		final ChainFleet fleet = group(ChainFleet.everyMember(false));
		final List<Entry> own = entries(fleet, 1, 2);
		final ChainHost exit = fleet.host(EXIT);

		postStem(fleet, own.get(0));

		final String successor = stemmedTo(writtenBy(fleet, EXIT)).getKey();
		final String other = drawnBy(fleet, EXIT, 1).stream().filter(id -> !id.equals(successor)).findFirst()
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

	/* The first member to flood an entry of a session is the first one on the route of its writer that diffuses it,
	   and the route is drawn once for the life of the writer. However long a member watches who floods the entries of
	   one session first, it sees one member, or two once the writer moved, and never the exit while no embargo fired,
	   so it rules out no more members over time than it did at the start. */
	@Property(tries = 20)
	public void theFirstFloodersOfASessionStayTheSameForHalfAnHour(@ForAll long seed) {
		final ChainFleet fleet = ChainFleet.group(8, ChainFleet.settings(8, 4, 16, 30, 3), seeded(seed));
		final FeedWriter session = new FeedWriter(ChainFleet.key(1), fleet.chain());
		final List<Entry> posted = new ArrayList<>();

		for (int minute = 0; minute < 40; minute++) {
			for (final Entry entry : ChainFleet.write(session, 2, fleet.clock.get())) {
				assertThat(postStem(fleet, entry), is(in(PASSED_ON.values())));
				posted.add(entry);
				fleet.deliver();
			}

			fleet.clock.addAndGet(MINUTE);
		}

		final Map<Entry, String> firstFlooder = firstFlooders(fleet);
		final Set<String> seen = posted.stream().filter(firstFlooder::containsKey).map(firstFlooder::get)
			.collect(Collectors.toSet());

		assertThat(seen.size(), is(lessThanOrEqualTo(2)));
		assertThat(EXIT, is(not(in(seen))));
	}

	/* A relay keeps what it drew for a writer whatever the clock reads: it floods every entry of a writer it diffuses
	   and passes every entry of one it relays to the same successor, for half an hour and longer */
	@Property(tries = 20)
	public void aRelayKeepsTheModeAndTheRouteOfAWriterForHalfAnHour(@ForAll long seed) {
		final ChainFleet fleet = group(seeded(seed));
		final FeedWriter writer = new FeedWriter(ChainFleet.key(2), fleet.chain());
		final Set<Map<String, Class<?>>> handled = new HashSet<>();

		for (int minute = 0; minute < 40; minute++) {
			final Map<String, Class<?>> sent = new LinkedHashMap<>();

			assertThat(stem(fleet, RELAY, "gn-1", ChainFleet.write(writer, 1, fleet.clock.get())), is(empty()));
			writtenBy(fleet, RELAY).forEach((peer, packets) -> packets.forEach(packet -> sent.put(peer,
				packet.getClass())));
			handled.add(sent);
			fleet.clock.addAndGet(MINUTE);
		}

		assertThat(handled, hasSize(1));
		assertThat(handled.iterator().next().size(), is(in(List.of(1, OTHERS.size()))));
	}
}
