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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class StemEmbargoTest {
	private static final long NOW = ChainFleet.NOW;
	private static final long LONG_AFTER = NOW + 600_000;
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);
	private static final Channel SUCCESSOR = new EmbeddedChannel();

	private static List<Entry> entries(final int writer, final int count) {
		return ChainFleet.entries(ChainFleet.key(writer), CHAIN, count, NOW);
	}

	/* When each entry was first handed back, stepping the clock a tenth of a second at a time */
	private static Map<Entry, Long> firstDue(final StemEmbargo embargo, final long until) {
		final Map<Entry, Long> due = new HashMap<>();

		for (long now = NOW; now <= until; now += 100) {
			final long at = now;

			embargo.due(now).forEach(entry -> due.putIfAbsent(entry, at - NOW));
		}

		return due;
	}

	@Property(tries = 10)
	public void floodsWhatDidNotComeBack(@ForAll long seed) {
		final StemEmbargo embargo = new StemEmbargo(new SplittableRandom(seed));
		final List<Entry> entries = entries(1, 3);

		entries.forEach(entry -> assertThat(embargo.hold(entry, false, NOW), is(true)));
		entries.forEach(entry -> embargo.sent(entry, SUCCESSOR));
		assertThat(embargo.holdsOwn(entries.get(0)), is(false));
		assertThat(embargo.release(entries.get(1)), is(true));
		assertThat(embargo.due(NOW + StemEmbargo.RELAY_MILLIS - 1), is(empty()));
		assertThat(embargo.due(LONG_AFTER), is(List.of(entries.get(0), entries.get(2))));
		assertThat(embargo.due(LONG_AFTER), is(empty()));
		assertThat(embargo.bytes(), is(0L));
		assertThat(embargo.holdsAnyOf(entries.get(0).header().writer()), is(false));
	}

	/* What a relay never passed on, as its route took no writes, waits past its deadline as long as it never leaves,
	   one more deadline missed on that route at a time, and is let go only once it was passed on */
	@Property(tries = 10)
	public void holdsWhatNeverLeftPastItsDeadline(@ForAll long seed) {
		final StemEmbargo embargo = new StemEmbargo(new SplittableRandom(seed));
		final Entry entry = entries(1, 1).get(0);

		embargo.hold(entry, false, NOW);
		embargo.tried(entry, SUCCESSOR, NOW, true);

		assertThat(embargo.waitingFor(SUCCESSOR), is(List.of(entry)));
		assertThat(embargo.waited(LONG_AFTER).get(0).waiting(), equalTo(new StemEmbargo.Waiting(SUCCESSOR, NOW, 1,
			StemEmbargo.Try.MISSED)));
		assertThat(embargo.waited(LONG_AFTER * 2).get(0).waiting().missed(), is(2));
		assertThat(embargo.holds(entry), is(true));

		embargo.sent(entry, SUCCESSOR);

		assertThat(embargo.waitingFor(SUCCESSOR), is(empty()));
		assertThat(embargo.waited(LONG_AFTER * 3).get(0).sent(), is(true));
		assertThat(embargo.holds(entry), is(false));
	}

	/* The exit floods its own entry once no relay did, and keeps it until it comes back, as its own replica may have had
	   no room for it */
	@Property(tries = 10)
	public void keepsOwnEntriesUntilTheyComeBack(@ForAll long seed) {
		final StemEmbargo embargo = new StemEmbargo(new SplittableRandom(seed));
		final List<Entry> entries = entries(1, 3);
		final WriterId writer = entries.get(0).header().writer();

		entries.forEach(entry -> embargo.hold(entry, true, NOW));
		assertThat(embargo.hold(entries.get(0), true, NOW), is(false));
		assertThat(embargo.holdsOwn(entries.get(0)), is(true));
		assertThat(embargo.bytes(), is(3L * entries.get(0).size()));
		assertThat(embargo.ownBytes(), is(3L * entries.get(0).size()));
		assertThat(embargo.relayedBytes(), is(0L));
		assertThat(embargo.due(NOW + StemEmbargo.OWN_MILLIS - 1), is(empty()));
		assertThat(embargo.due(LONG_AFTER), is(entries));
		assertThat(embargo.from(writer, 1), is(entries.subList(1, 3)));
		entries.forEach(embargo::release);
		assertThat(embargo.holdsAnyOf(writer), is(false));
		assertThat(embargo.bytes(), is(0L));
		assertThat(embargo.due(LONG_AFTER * 2), is(empty()));
	}

	@Example
	public void exitWaitsLongerThanRelays() {
		final StemEmbargo embargo = new StemEmbargo(new SplittableRandom(11));
		final List<Entry> relayed = entries(1, 200);
		final List<Entry> own = entries(2, 200);

		relayed.forEach(entry -> embargo.hold(entry, false, NOW));
		own.forEach(entry -> embargo.hold(entry, true, NOW));

		final Map<Entry, Long> due = firstDue(embargo, NOW + 120_000);
		final List<Long> relays = relayed.stream().map(due::get).toList();
		final List<Long> exits = own.stream().map(due::get).toList();

		assertThat(relays.stream().mapToLong(Long::longValue).min().orElseThrow(),
			is(greaterThanOrEqualTo(StemEmbargo.RELAY_MILLIS)));
		assertThat(exits.stream().mapToLong(Long::longValue).min().orElseThrow(),
			is(greaterThanOrEqualTo(StemEmbargo.OWN_MILLIS)));
		assertThat(mean(relays), is(greaterThan(3_000.0)));
		assertThat(mean(relays), is(lessThan(5_000.0)));
		assertThat(mean(exits), is(greaterThan(6_000.0)));
		assertThat(mean(exits), is(lessThan(10_000.0)));
	}

	private static double mean(final List<Long> delays) {
		return delays.stream().mapToLong(Long::longValue).average().orElseThrow();
	}
}
