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

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostStemTest {
	private static final long NOW = ChainFleet.NOW;
	private static final String RELAY = "gn-0";
	private static final List<String> OTHERS = List.of("gn-1", "gn-2", "gn-3");
	private static final int MAX_ROUNDS = 25;

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
}
