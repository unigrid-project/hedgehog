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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class StemRouterTest {
	private static final long NOW = ChainFleet.NOW;
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);
	private static final int WRITERS = 40;

	private static List<Channel> streams(final int count) {
		return IntStream.range(0, count).mapToObj(stream -> (Channel) new EmbeddedChannel()).toList();
	}

	private static StemRouter router(final long seed, final List<Channel> members) {
		return new StemRouter(new SplittableRandom(seed), chainId -> members);
	}

	/* The successors of the writers of one period, as the router routes them at the given time */
	private static List<Optional<Channel>> routes(final StemRouter router, final long now) {
		return IntStream.range(0, WRITERS).mapToObj(writer -> router.successor(CHAIN, ChainFleet.writer(writer),
			Set.of(), now)).toList();
	}

	@Property(tries = 20)
	public void modeAndSuccessorsHoldForAPeriod(@ForAll long seed, @ForAll @IntRange(min = 1, max = 59) int later) {
		final List<Channel> members = streams(6);
		final StemRouter router = router(seed, members);
		final long start = Math.floorDiv(NOW, StemRouter.PERIOD_MILLIS) * StemRouter.PERIOD_MILLIS;
		final boolean diffuses = router.diffuses(CHAIN, start);
		final List<Optional<Channel>> routes = routes(router, start);
		final long within = start + later * 1000L;

		assertThat(router.diffuses(CHAIN, within), is(diffuses));
		assertThat(routes(router, within), is(routes));
		assertThat(routes.stream().flatMap(Optional::stream).collect(Collectors.toSet()).size(),
			is(lessThanOrEqualTo(StemRouter.SUCCESSORS)));
		assertThat(routes.stream().flatMap(Optional::stream).toList(), everyItem(is(in(members))));
	}

	/* A new period draws the mode and the successors again, so over many periods both modes and every member turn up */
	@Example
	public void eachPeriodDrawsAgain() {
		final List<Channel> members = streams(6);
		final StemRouter router = router(7, members);
		final Set<Boolean> modes = new HashSet<>();
		final Set<Channel> successors = new HashSet<>();

		LongStream.range(0, 50).map(period -> NOW + period * StemRouter.PERIOD_MILLIS).forEach(now -> {
			modes.add(router.diffuses(CHAIN, now));
			routes(router, now).forEach(route -> route.ifPresent(successors::add));
		});

		assertThat(modes, hasSize(2));
		assertThat(successors, is(Set.copyOf(members)));
	}

	@Property(tries = 50)
	public void neverAnAvoidedStream(@ForAll long seed, @ForAll @IntRange(min = 1, max = 5) int count,
		@ForAll @IntRange(min = 0, max = 5) int avoided) {

		final List<Channel> members = streams(count);
		final Set<Channel> avoid = Set.copyOf(members.subList(0, Math.min(avoided, count)));
		final StemRouter router = router(seed, members);
		final WriterId writer = ChainFleet.writer(1);

		router.successor(CHAIN, writer, Set.of(), NOW);

		for (int call = 0; call < 5; call++) {
			router.successor(CHAIN, writer, avoid, NOW).ifPresent(successor ->
				assertThat(successor, is(not(in(avoid)))));
		}

		assertThat(router.hasSuccessor(CHAIN, avoid, NOW),
			is(router.successor(CHAIN, writer, avoid, NOW).isPresent()));
	}

	/* With a successor left outside what is avoided, a writer always has somewhere to go */
	@Property(tries = 20)
	public void avoidsOneSuccessorByTakingTheOther(@ForAll long seed) {
		final List<Channel> members = streams(2);
		final StemRouter router = router(seed, members);

		for (final Channel avoided : members) {
			assertThat(router.successor(CHAIN, ChainFleet.writer(1), Set.of(avoided), NOW).orElseThrow(),
				is(not(avoided)));
		}

		assertThat(router.successor(CHAIN, ChainFleet.writer(1), Set.copyOf(members), NOW), is(Optional.empty()));
		assertThat(router.hasSuccessor(CHAIN, Set.copyOf(members), NOW), is(false));
	}

	/* A stream that closed or that is no longer one of the members is no successor, and nothing is drawn in its place
	   while the other is there; once both went, a writer is routed to another member for the period */
	@Example
	public void passesOverASuccessorThatWentAwayAndReplacesBothOnceBothWent() {
		final List<Channel> members = new ArrayList<>(streams(2));
		final StemRouter router = new StemRouter(new SplittableRandom(3), chainId -> List.copyOf(members));
		final WriterId writer = ChainFleet.writer(1);
		final Channel first = router.successor(CHAIN, writer, Set.of(), NOW).orElseThrow();
		final Channel other = members.get(0) == first ? members.get(1) : members.get(0);
		final Channel joined = new EmbeddedChannel();

		first.close();
		assertThat(router.successor(CHAIN, writer, Set.of(), NOW).orElseThrow(), is(other));
		members.remove(other);
		members.add(joined);
		assertThat(router.successor(CHAIN, writer, Set.of(), NOW), is(Optional.of(joined)));
	}

	/* A successor that refused an entry of a writer is never replaced by a member outside the draw of the period,
	   however often the successors refuse, so refusals cannot walk the stem of a writer to a member of their choice */
	@Property(tries = 20)
	public void routesAWriterToNoMemberOutsideTheDrawWhateverItsSuccessorsRefuse(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final StemRouter router = router(seed, members);
		final Set<Channel> drawn = routes(router, NOW).stream().flatMap(Optional::stream).collect(Collectors.toSet());
		final WriterId writer = ChainFleet.writer(WRITERS + 1);

		for (int refusal = 0; refusal < 4; refusal++) {
			router.successor(CHAIN, writer, Set.of(), NOW).ifPresent(successor -> router.refused(CHAIN, writer,
				successor, Set.of(), NOW));
		}

		router.successor(CHAIN, writer, Set.of(), NOW).ifPresent(successor ->
			assertThat(successor, is(in(drawn))));
		assertThat(drawn, hasSize(StemRouter.SUCCESSORS));
	}

	@Example
	public void aboutHalfTheMembersDiffuse() {
		final long diffusers = LongStream.range(0, 1000).filter(seed -> router(seed, streams(3)).diffuses(CHAIN, NOW))
			.count();

		assertThat(diffusers, is(greaterThan(430L)));
		assertThat(diffusers, is(lessThan(570L)));
	}

	@Example
	public void forgetsThePeriodsThatAreOver() {
		final List<Channel> members = streams(3);
		final StemRouter router = router(5, members);

		router.diffuses(CHAIN, NOW);
		assertThat(router.periods(), is(1));
		router.forgetBefore(NOW);
		assertThat(router.periods(), is(1));
		router.forgetBefore(NOW + StemRouter.PERIOD_MILLIS);
		assertThat(router.periods(), is(0));
	}

	/* An exit avoids both members its client attached through, which may be both successors of the period: the writer
	   is then routed to another member for the period, and has none only when every member is avoided */
	@Property(tries = 20)
	public void routesAWriterWhoseSuccessorsAreBothAvoidedToAnotherMember(@ForAll long seed) {
		final List<Channel> members = streams(4);
		final StemRouter router = router(seed, members);
		final Set<Channel> successors = routes(router, NOW).stream().flatMap(Optional::stream)
			.collect(Collectors.toSet());
		final WriterId writer = ChainFleet.writer(WRITERS + 1);
		final Channel other = router.successor(CHAIN, writer, successors, NOW).orElseThrow();

		assertThat(successors, hasSize(StemRouter.SUCCESSORS));
		assertThat(other, is(not(in(successors))));
		assertThat(router.successor(CHAIN, writer, successors, NOW + 1000), is(Optional.of(other)));
		assertThat(router.hasSuccessor(CHAIN, successors, NOW), is(true));
		assertThat(router.successor(CHAIN, writer, Set.copyOf(members), NOW), is(Optional.empty()));
		assertThat(router.hasSuccessor(CHAIN, Set.copyOf(members), NOW), is(false));
	}

	/* Past the most writers the router keeps a route for, a writer goes by a hash of its id under a key of the period,
	   so it keeps one successor for the period without the router holding anything for it */
	@Property(tries = 10)
	public void routesWritersPastTheCapByAKeyedHashWithoutKeepingThem(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final StemRouter router = new StemRouter(new SplittableRandom(seed), chainId -> members, 2);
		final Set<Channel> drawn = new HashSet<>();

		for (int writer = 0; writer < WRITERS; writer++) {
			final Optional<Channel> first = router.successor(CHAIN, ChainFleet.writer(writer), Set.of(), NOW);

			assertThat(router.successor(CHAIN, ChainFleet.writer(writer), Set.of(), NOW + 1000), is(first));
			first.ifPresent(drawn::add);
		}

		assertThat(router.routed(CHAIN, NOW), is(2));
		assertThat(drawn, hasSize(StemRouter.SUCCESSORS));

		for (final Channel avoided : drawn) {
			assertThat(router.successor(CHAIN, ChainFleet.writer(WRITERS - 1), Set.of(avoided), NOW).orElseThrow(),
				is(not(avoided)));
		}
	}
}
