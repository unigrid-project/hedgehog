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
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class StemRouterTest {
	private static final int WRITERS = 40;

	private static List<Channel> streams(final int count) {
		return IntStream.range(0, count).mapToObj(stream -> (Channel) new EmbeddedChannel()).toList();
	}

	private static StemRouter router(final long seed, final List<Channel> members) {
		return new StemRouter(new SplittableRandom(seed), () -> members, HostedChain.MAX_WRITERS);
	}

	/* The draw of a writer this member is passed an entry of from the source, kept as when the entry is taken */
	private static StemRouter.Draw relayed(final StemRouter router, final WriterId writer, final Channel source) {
		final StemRouter.Draw draw = router.drawFor(writer, source);

		router.keep(writer, draw, source);
		return draw;
	}

	/* Whatever it is passed later and from wherever, a writer keeps its mode and its route: a writer it relays goes to
	   the first of two successors drawn apart from the stream it came from, and one it diffuses has none */
	@Property(tries = 20)
	public void keepsTheModeAndTheRouteOfAWriterForItsLife(@ForAll long seed) {
		final List<Channel> members = streams(6);
		final StemRouter router = router(seed, members);
		final Channel source = members.get(0);

		for (int number = 0; number < WRITERS; number++) {
			final WriterId writer = ChainFleet.writer(number);
			final StemRouter.Draw draw = relayed(router, writer, source);
			final Optional<Channel> route = router.successor(writer, Set.of(source));

			for (int call = 0; call < 10; call++) {
				assertThat(router.drawFor(writer, members.get(call % members.size())), is(sameInstance(draw)));
				assertThat(router.successor(writer, Set.of(source)), is(route));
			}

			if (!draw.diffuses()) {
				assertThat(router.drawn(writer), hasSize(StemRouter.SUCCESSORS));
				assertThat(Set.copyOf(router.drawn(writer)), hasSize(StemRouter.SUCCESSORS));
				assertThat(source, is(not(in(router.drawn(writer)))));
				assertThat(route, is(Optional.of(router.drawn(writer).get(0))));
			}
		}
	}

	/* Each writer is drawn apart, so a member diffuses some writers and relays others, and their routes go to every
	   member but the one they came from */
	@Example
	public void drawsEachWriterApart() {
		final List<Channel> members = streams(6);
		final StemRouter router = router(7, members);
		final Set<Boolean> modes = new HashSet<>();
		final Set<Channel> routes = new HashSet<>();

		for (int number = 0; number < WRITERS; number++) {
			final WriterId writer = ChainFleet.writer(number);

			modes.add(relayed(router, writer, members.get(0)).diffuses());
			router.successor(writer, Set.of(members.get(0))).ifPresent(routes::add);
		}

		assertThat(modes, hasSize(2));
		assertThat(routes, is(Set.copyOf(members.subList(1, members.size()))));
	}

	/* Two writers are drawn apart: over many members, one relays the first and diffuses the second about as often as
	   the other way round, and as often as it does the same with both */
	@Example
	public void relaysOneWriterAndDiffusesAnotherAsOftenAsChanceHasIt() {
		final int[] counts = new int[4];

		for (long seed = 0; seed < 1000; seed++) {
			final List<Channel> members = streams(3);
			final StemRouter router = router(seed, members);
			final boolean first = relayed(router, ChainFleet.writer(1), members.get(0)).diffuses();
			final boolean second = relayed(router, ChainFleet.writer(2), members.get(0)).diffuses();

			counts[(first ? 2 : 0) + (second ? 1 : 0)]++;
		}

		for (final int count : counts) {
			assertThat(count, is(greaterThan(200)));
			assertThat(count, is(lessThan(300)));
		}
	}

	@Example
	public void aboutHalfTheWritersAreDiffused() {
		final List<Channel> members = streams(3);
		final StemRouter router = router(5, members);
		final long diffused = LongStream.range(0, 1000)
			.filter(number -> relayed(router, ChainFleet.writer((int) number), members.get(0)).diffuses()).count();

		assertThat(diffused, is(greaterThan(430L)));
		assertThat(diffused, is(lessThan(570L)));
	}

	/* A draw is kept only once an entry of the writer is taken, so the entries a chain turns away hold nothing */
	@Example
	public void keepsNoDrawOfAWriterWhoseEntryWasNotTaken() {
		final List<Channel> members = streams(3);
		final StemRouter router = router(5, members);
		final StemRouter.Draw draw = router.drawFor(ChainFleet.writer(1), members.get(0));

		assertThat(router.draws(), is(0));
		assertThat(router.drawFor(ChainFleet.writer(1), members.get(0)), is(not(sameInstance(draw))));
		assertThat(router.drawn(ChainFleet.writer(1)), is(empty()));
	}

	/* A writer of this member's own is never diffused, and its successors are drawn outside what it avoids */
	@Property(tries = 20)
	public void drawsAWriterOfItsOwnOutsideWhatItAvoids(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final Set<Channel> avoid = Set.copyOf(members.subList(0, 2));
		final StemRouter router = router(seed, members);
		final WriterId writer = ChainFleet.writer(1);
		final Channel route = router.ownSuccessor(writer, avoid).orElseThrow();

		assertThat(route, is(router.drawn(writer).get(0)));
		assertThat(router.drawn(writer), hasSize(StemRouter.SUCCESSORS));
		router.drawn(writer).forEach(successor -> assertThat(successor, is(not(in(avoid)))));
		assertThat(router.ownSuccessor(writer, avoid), is(Optional.of(route)));
	}

	@Property(tries = 50)
	public void neverAnAvoidedStream(@ForAll long seed, @ForAll @IntRange(min = 1, max = 5) int count,
		@ForAll @IntRange(min = 0, max = 5) int avoided) {

		final List<Channel> members = streams(count);
		final Set<Channel> avoid = Set.copyOf(members.subList(0, Math.min(avoided, count)));
		final StemRouter router = router(seed, members);
		final WriterId writer = ChainFleet.writer(1);

		router.ownSuccessor(writer, Set.of());

		for (int call = 0; call < 5; call++) {
			router.ownSuccessor(writer, avoid).ifPresent(successor -> assertThat(successor, is(not(in(avoid)))));
		}

		assertThat(router.hasSuccessor(avoid), is(router.ownSuccessor(writer, avoid).isPresent()));
	}

	/* With no member to replace an avoided route by, a writer goes to its other successor while that one is there */
	@Property(tries = 20)
	public void avoidsOneSuccessorByTakingTheOther(@ForAll long seed) {
		final List<Channel> members = streams(2);
		final StemRouter router = router(seed, members);
		final Channel route = router.ownSuccessor(ChainFleet.writer(1), Set.of()).orElseThrow();

		assertThat(router.ownSuccessor(ChainFleet.writer(1), Set.of(route)).orElseThrow(), is(not(route)));
		assertThat(router.ownSuccessor(ChainFleet.writer(1), Set.copyOf(members)), is(Optional.empty()));
		assertThat(router.hasSuccessor(Set.copyOf(members)), is(false));
	}

	/* A stream that closed or that is no longer one of the members leaves the writer to its other successor, and
	   nothing is drawn while that one is there. Once both went, one member is drawn outside the draw, and once that
	   one went too, another: two in all for the life of the writer, and none after them, whatever members are left. */
	@Property(tries = 20)
	public void drawsOneSuccessorOnceBothWentAndNoMoreThanTwice(@ForAll long seed) {
		final List<Channel> members = new ArrayList<>(streams(8));
		final StemRouter router = new StemRouter(new SplittableRandom(seed), () -> List.copyOf(members),
			HostedChain.MAX_WRITERS);
		final WriterId writer = ChainFleet.writer(1);
		final List<Channel> drawn = router.drawn(writer);
		final Channel first = router.ownSuccessor(writer, Set.of()).orElseThrow();
		final Channel second = router.drawn(writer).get(1);
		final Set<Channel> gone = new HashSet<>(List.of(first, second));

		assertThat(drawn, is(empty()));
		first.close();
		assertThat(router.ownSuccessor(writer, Set.of()), is(Optional.of(second)));
		assertThat(router.drawn(writer), equalTo(List.of(second)));
		members.remove(second);

		for (int replacement = 0; replacement < 2; replacement++) {
			final Channel drew = router.ownSuccessor(writer, Set.of()).orElseThrow();

			assertThat(drew, is(not(in(gone))));
			assertThat(router.drawn(writer), equalTo(List.of(drew)));
			gone.add(drew);
			drew.close();
		}

		assertThat(router.ownSuccessor(writer, Set.of()), is(Optional.empty()));
		assertThat(router.hasSuccessor(Set.of()), is(true));
	}

	/* A writer drawn while no member was there gets its first successors once members are, which spends none of the
	   replacements it has once both of them went */
	@Example
	public void aDrawWhileNoMemberWasThereSpendsNoReplacement() {
		final List<Channel> members = new ArrayList<>();
		final StemRouter router = new StemRouter(new SplittableRandom(5), () -> List.copyOf(members),
			HostedChain.MAX_WRITERS);
		final WriterId writer = ChainFleet.writer(1);
		int rounds = 0;

		assertThat(router.ownSuccessor(writer, Set.of()), is(Optional.empty()));
		members.addAll(streams(8));
		assertThat(router.ownSuccessor(writer, Set.of()).isPresent(), is(true));
		assertThat(router.drawn(writer), hasSize(StemRouter.SUCCESSORS));

		for (List<Channel> left = router.drawn(writer); !left.isEmpty(); left = router.drawn(writer)) {
			left.forEach(Channel::close);
			router.ownSuccessor(writer, Set.of());
			rounds++;
		}

		assertThat(rounds, is(1 + StemRouter.REPLACEMENTS));
	}

	/* A refusal moves a writer to its other successor once for its life, at the author and at a relay alike, and a
	   second leaves it none, so refusals cannot walk the stem of a writer to a member outside its draw */
	@Property(tries = 20)
	public void movesAWriterOnceWhateverItsSuccessorsRefuse(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final StemRouter router = router(seed, members);
		final WriterId own = ChainFleet.writer(1);
		final WriterId relayed = ChainFleet.writer(2);

		router.ownSuccessor(own, Set.of());
		relayed(router, relayed, members.get(0));

		for (final WriterId writer : router.drawn(relayed).isEmpty() ? List.of(own) : List.of(own, relayed)) {
			final List<Channel> drawn = router.drawn(writer);
			final Channel route = router.successor(writer, Set.of()).orElseThrow();

			assertThat(router.refused(writer, route, Set.of()), is(Optional.of(drawn.get(1))));
			assertThat(router.successor(writer, Set.of()), is(Optional.of(drawn.get(1))));
			assertThat(router.refused(writer, drawn.get(1), Set.of()), is(Optional.empty()));

			for (int refusal = 0; refusal < 4; refusal++) {
				assertThat(router.successor(writer, Set.of()), is(Optional.empty()));
				members.forEach(member -> assertThat(router.refused(writer, member, Set.of()), is(Optional.empty())));
			}

			assertThat(router.strands(writer, Set.of()), is(true));
		}
	}

	/* A round of the stem moves a writer to its other successor as a refusal does, and spends the same one move */
	@Property(tries = 20)
	public void aRoundAndARefusalShareTheOneMoveOfAWriter(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final StemRouter router = router(seed, members);
		final WriterId first = ChainFleet.writer(1);
		final WriterId second = ChainFleet.writer(2);

		router.ownSuccessor(first, Set.of());
		router.ownSuccessor(second, Set.of());

		final List<Channel> firstDrew = router.drawn(first);
		final List<Channel> secondDrew = router.drawn(second);

		router.cameRound(first, Set.of());

		assertThat(router.ownSuccessor(first, Set.of()), is(Optional.of(firstDrew.get(1))));
		assertThat(router.refused(first, firstDrew.get(1), Set.of()), is(Optional.empty()));

		router.refused(second, secondDrew.get(0), Set.of());
		router.cameRound(second, Set.of());

		assertThat(router.ownSuccessor(second, Set.of()), is(Optional.of(secondDrew.get(1))));
	}

	/* An exit avoids both members its client attached through, which may come to be both successors of its writer:
	   a member outside the draw is then drawn in their place, and the writer has none only when every member is
	   avoided */
	@Property(tries = 20)
	public void drawsAnotherSuccessorOnceAWriterAvoidsBoth(@ForAll long seed) {
		final List<Channel> members = streams(4);
		final StemRouter router = router(seed, members);
		final WriterId writer = ChainFleet.writer(1);

		router.ownSuccessor(writer, Set.of());

		final Set<Channel> successors = Set.copyOf(router.drawn(writer));
		final Channel other = router.ownSuccessor(writer, successors).orElseThrow();

		assertThat(successors, hasSize(StemRouter.SUCCESSORS));
		assertThat(other, is(not(in(successors))));
		assertThat(router.drawn(writer), equalTo(List.of(other)));
		assertThat(router.ownSuccessor(writer, successors), is(Optional.of(other)));
		assertThat(router.hasSuccessor(successors), is(true));
		assertThat(router.ownSuccessor(writer, Set.copyOf(members)), is(Optional.empty()));
		assertThat(router.hasSuccessor(Set.copyOf(members)), is(false));
	}

	/* Past the most writers the router keeps a draw for, a writer goes by a hash of its id under a key of the chain,
	   so it keeps one mode and one successor without the router holding anything for it, and a refusal moves it
	   nowhere */
	@Property(tries = 10)
	public void routesWritersPastTheCapByAKeyedHashWithoutKeepingThem(@ForAll long seed) {
		final List<Channel> members = streams(5);
		final StemRouter router = new StemRouter(new SplittableRandom(seed), () -> members, 2);
		final Channel source = members.get(0);
		final Set<Boolean> modes = new HashSet<>();

		for (int number = 0; number < WRITERS; number++) {
			final WriterId writer = ChainFleet.writer(number);
			final boolean diffuses = relayed(router, writer, source).diffuses();
			final Optional<Channel> first = router.successor(writer, Set.of(source));

			for (int call = 0; call < 5; call++) {
				assertThat(router.drawFor(writer, source).diffuses(), is(diffuses));
				assertThat(router.successor(writer, Set.of(source)), is(first));
			}

			first.ifPresent(successor -> assertThat(successor, is(not(source))));
			modes.add(diffuses);

			if (number >= 2) {
				assertThat(router.refused(writer, first.orElseThrow(), Set.of()), is(Optional.empty()));
			}
		}

		assertThat(router.draws(), is(2));
		assertThat(modes, hasSize(2));
	}
}
