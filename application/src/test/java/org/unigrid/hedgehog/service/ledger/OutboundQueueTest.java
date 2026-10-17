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

package org.unigrid.hedgehog.service.ledger;

import java.util.Random;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.service.ledger.OutboundQueue.Lane.BULK;
import static org.unigrid.hedgehog.service.ledger.OutboundQueue.Lane.VOTES;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Proposal;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.service.ledger.OutboundQueue.Lane;

public class OutboundQueueTest {
	private static Packet packetOf(final int seed) {
		return LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build();
	}

	@Property(tries = 40)
	public void putsEveryBallotHeartbeatAndStatusInTheLaneOfVotes(@ForAll @IntRange(min = 0, max = 1000) int seed) {
		final Random random = new Random(seed);
		final Ballot ballot = LedgerWireFixtures.ballot(random, Ballot.Type.values()[seed % 2], 1 + seed, 0,
			LedgerWireFixtures.random(random, Digests.HASH_SIZE));

		assertThat(Lane.of(ballot), is(VOTES));
		assertThat(Lane.of(LedgerWireFixtures.heartbeat(seed)), is(VOTES));
		assertThat(Lane.of(LedgerWireFixtures.status(seed)), is(VOTES));
	}

	@Property(tries = 40)
	public void putsEveryBlockAndTransactionInTheLaneOfTheRest(@ForAll @IntRange(min = 0, max = 1000) int seed) {
		final Random random = new Random(seed);
		final Block block = LedgerWireFixtures.block(random, 1 + seed);

		assertThat(Lane.of(new Decision(block)), is(BULK));
		assertThat(Lane.of(LedgerWireFixtures.proposal(random, block, seed % 4)), is(BULK));
		assertThat(Lane.of(LedgerWireFixtures.transaction(seed)), is(BULK));
	}

	@Property(tries = 40)
	public void countsTheEncodedBytesOfWhatIsSent(@ForAll @IntRange(min = 0, max = 1000) int seed) {
		final Message message = LedgerWireFixtures.message(seed);

		assertThat(OutboundQueue.sizeOf(message), is(WireCodec.encodeMessage(message).length));
		assertThat(OutboundQueue.sizeOf(LedgerWireFixtures.transaction(seed)),
			is(LedgerWireFixtures.transaction(seed).encode().length));
		assertThat(OutboundQueue.sizeOf(LedgerWireFixtures.heartbeat(seed)),
			is(WireCodec.encode(LedgerWireFixtures.heartbeat(seed)).length));
		assertThat(OutboundQueue.sizeOf(LedgerWireFixtures.status(seed)),
			is(WireCodec.encode(LedgerWireFixtures.status(seed)).length));
	}

	@Property(tries = 40)
	public void countsNoVoteAsMoreThanTheLargestVote(@ForAll @IntRange(min = 0, max = 1000) int seed) {
		final Random random = new Random(seed);
		final Ballot ballot = LedgerWireFixtures.ballot(random, Ballot.Type.values()[seed % 2], 1 + seed, seed,
			LedgerWireFixtures.random(random, Digests.HASH_SIZE));

		assertThat(OutboundQueue.sizeOf(ballot), lessThanOrEqualTo(OutboundQueue.LARGEST_VOTE));
		assertThat(OutboundQueue.sizeOf(LedgerWireFixtures.heartbeat(seed)),
			lessThanOrEqualTo(OutboundQueue.LARGEST_VOTE));
		assertThat(OutboundQueue.sizeOf(LedgerWireFixtures.status(seed)),
			lessThanOrEqualTo(OutboundQueue.LARGEST_VOTE));
	}

	@Example
	public void holdsAFewHundredVotesAndTwoOfTheLargestMessages() {
		assertThat(VOTES.limit() >= 256 * OutboundQueue.LARGEST_VOTE, is(true));
		assertThat(BULK.limit() >= 2 * WireCodec.MAX_MESSAGE_SIZE, is(true));
	}

	@Example
	public void refusesWhatDoesNotFitInTheLaneOfTheRestAndKeepsWhatItHas() {
		final OutboundQueue queue = new OutboundQueue();

		assertThat(queue.offer(BULK, packetOf(1), BULK.limit() - 10), is(0));
		assertThat(queue.offer(BULK, packetOf(2), 11), is(1));
		assertThat(queue.offer(BULK, packetOf(3), 10), is(0));
		assertThat(queue.offer(BULK, packetOf(4), 1), is(1));
		assertThat(queue.poll().get(), equalTo(packetOf(1)));
		assertThat(queue.poll().get(), equalTo(packetOf(3)));
		assertThat(queue.poll().isPresent(), is(false));
	}

	@Example
	public void dropsTheOldestVotesToMakeRoomForANewOne() {
		final OutboundQueue queue = new OutboundQueue();

		assertThat(queue.offer(VOTES, packetOf(1), VOTES.limit() - 10), is(0));
		assertThat(queue.offer(VOTES, packetOf(2), 10), is(0));
		assertThat(queue.offer(VOTES, packetOf(3), 1), is(1));
		assertThat(queue.size(), is(2));
		assertThat(queue.poll().get(), equalTo(packetOf(2)));
		assertThat(queue.poll().get(), equalTo(packetOf(3)));
		assertThat(queue.poll().isPresent(), is(false));
	}

	@Example
	public void dropsAsManyOfTheOldestVotesAsTheNewOneTakesTheRoomOf() {
		final OutboundQueue queue = new OutboundQueue();

		IntStream.range(0, 4).forEach(n -> queue.offer(VOTES, packetOf(n), VOTES.limit() / 4));

		assertThat(queue.offer(VOTES, packetOf(4), VOTES.limit() / 2 + 1), is(3));
		assertThat(queue.size(), is(2));
		assertThat(queue.poll().get(), equalTo(packetOf(3)));
		assertThat(queue.poll().get(), equalTo(packetOf(4)));
	}

	@Example
	public void refusesAMessageThatIsLargerThanItsLaneAndKeepsWhatWaits() {
		final OutboundQueue queue = new OutboundQueue();

		queue.offer(VOTES, packetOf(1), 10);

		assertThat(queue.offer(VOTES, packetOf(2), VOTES.limit() + 1), is(1));
		assertThat(queue.size(), is(1));
		assertThat(queue.poll().get(), equalTo(packetOf(1)));
	}

	@Example
	public void givesBackTheRoomOfWhatIsTaken() {
		final OutboundQueue queue = new OutboundQueue();

		assertThat(queue.offer(BULK, packetOf(1), BULK.limit()), is(0));
		assertThat(queue.offer(BULK, packetOf(2), 1), is(1));
		assertThat(queue.poll().get(), equalTo(packetOf(1)));
		assertThat(queue.offer(BULK, packetOf(3), BULK.limit()), is(0));
	}

	@Example
	public void boundsEachLaneAlone() {
		final OutboundQueue queue = new OutboundQueue();

		assertThat(queue.offer(BULK, packetOf(1), BULK.limit()), is(0));
		assertThat(queue.offer(BULK, packetOf(2), 1), is(1));
		assertThat(queue.offer(VOTES, packetOf(3), VOTES.limit()), is(0));
		assertThat(queue.size(), is(2));
	}

	@Example
	public void takesTheVotesBeforeTheRestWhateverTheOrderTheyCame() {
		final OutboundQueue queue = new OutboundQueue();

		queue.offer(BULK, packetOf(1), 1);
		queue.offer(VOTES, packetOf(2), 1);
		queue.offer(BULK, packetOf(3), 1);
		queue.offer(VOTES, packetOf(4), 1);

		assertThat(queue.poll().get(), equalTo(packetOf(2)));
		assertThat(queue.poll().get(), equalTo(packetOf(4)));
		assertThat(queue.poll().get(), equalTo(packetOf(1)));
		assertThat(queue.poll().get(), equalTo(packetOf(3)));
	}

	@Example
	public void forgetsEverythingAndItsBytesWhenCleared() {
		final OutboundQueue queue = new OutboundQueue();

		queue.offer(VOTES, packetOf(1), VOTES.limit());
		queue.offer(BULK, packetOf(2), BULK.limit());
		queue.clear();

		assertThat(queue.isEmpty(), is(true));
		assertThat(queue.size(), is(0));
		assertThat(queue.offer(VOTES, packetOf(3), VOTES.limit()), is(0));
		assertThat(queue.offer(BULK, packetOf(4), BULK.limit()), is(0));
	}
}
