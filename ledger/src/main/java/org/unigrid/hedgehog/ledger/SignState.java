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

package org.unigrid.hedgehog.ledger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/* What a validator has signed at one height, and where it stands, so that a restart can carry on without ever
   signing something else for a view it has already signed in. It holds the node's own proposals and ballots as
   they were sent, the step and view, whether the engine runs, `nextView`, the first view in which nothing has been
   signed, and the locked and valid blocks with their views, which are -1 when there is no such block. What was only
   received is not held, as peers send it again.

   A node signs at most one proposal and two ballots (a prevote and a precommit) in a view, and no view above the
   current one, so a state holds at most `view + 1` proposals and `2 * (view + 1)` ballots. The constructor and the
   decoder both refuse more, the decoder before it reads any entry, so that a count in a damaged file costs
   nothing. The layout is the height, the running flag, the view, the step as its ordinal, `nextView`, each of the
   two blocks as a marker, the length of the block and the block, then its view, and finally the proposals and the
   ballots, each a count followed by the messages in the layout of the wire codec. */
public record SignState(long height, boolean running, int view, Consensus.Step step, int nextView, Block lockedBlock,
	int lockedView, Block validBlock, int validView, List<Proposal> proposals, List<Ballot> ballots) {
	private static final byte NO = 0;
	private static final byte YES = 1;
	private static final int HEAD_SIZE = Long.BYTES + Byte.BYTES + Integer.BYTES + Byte.BYTES + Integer.BYTES;
	private static final int OPTIONAL_BLOCK_SIZE = Byte.BYTES + Integer.BYTES + BlockCodec.MAX_ENCODED_SIZE
		+ Integer.BYTES;
	private static final int ENTRIES_PER_VIEW = 3;

	public SignState {
		Objects.requireNonNull(step);

		if (height < 0 || view < 0 || nextView < 0) {
			throw new IllegalArgumentException("A height, a view and a next view are 0 or more, found " + height
				+ ", " + view + " and " + nextView);
		}

		proposals = List.copyOf(proposals);
		ballots = List.copyOf(ballots);
		requireBlock("locked", lockedBlock, lockedView, view, height);
		requireBlock("valid", validBlock, validView, view, height);
		requireOneHeight(height, proposals, ballots);
		requireOnePerView(proposals, ballots);
		requireWhatOneNodeCanSign(view, proposals.size(), ballots.size());
	}

	public static SignState empty(long height) {
		return new SignState(height, false, 0, Consensus.Step.PROPOSE, 0, null, -1, null, -1, List.of(), List.of());
	}

	/* The most bytes a state can take up when it holds the entries of that many views */
	static long largestEncodedSize(int largestProof, int views) {
		return HEAD_SIZE + 2L * OPTIONAL_BLOCK_SIZE + 2L * Integer.BYTES
			+ (long) views * ENTRIES_PER_VIEW * ConsensusCodec.maxEncodedSize(largestProof);
	}

	public byte[] encode() {
		final byte[] head = ByteBuffer.allocate(HEAD_SIZE).putLong(height).put(flag(running)).putInt(view)
			.put((byte) step.ordinal()).putInt(nextView).array();

		return Bytes.concat(head, encodeBlock(lockedBlock), Bytes.intBytes(lockedView), encodeBlock(validBlock),
			Bytes.intBytes(validView), encodeMessages(proposals), encodeMessages(ballots));
	}

	public static SignState decode(byte[] data, int largestProof) {
		final ByteBuffer in = ByteBuffer.wrap(data);
		final long height = Bytes.takeLong(in);
		final boolean running = decodeFlag(Bytes.take(in, 1)[0]);
		final int view = decodeView(Bytes.takeInt(in));
		final Consensus.Step step = decodeStep(Bytes.take(in, 1)[0]);
		final int nextView = Bytes.takeInt(in);
		final Block lockedBlock = decodeBlock(in);
		final int lockedView = Bytes.takeInt(in);
		final Block validBlock = decodeBlock(in);
		final int validView = Bytes.takeInt(in);
		final List<Proposal> proposals = decodeMessages(in, view + 1L, largestProof, Proposal.class);
		final List<Ballot> ballots = decodeMessages(in, 2 * (view + 1L), largestProof, Ballot.class);

		if (in.hasRemaining()) {
			throw new IllegalArgumentException(in.remaining() + " bytes follow the sign state");
		}

		return new SignState(height, running, view, step, nextView, lockedBlock, lockedView, validBlock, validView,
			proposals, ballots);
	}

	private static void requireBlock(String name, Block block, int blockView, int view, long height) {
		final boolean viewFits = blockView >= 0 && blockView <= view;

		if (block == null ? blockView != -1 : !viewFits) {
			throw new IllegalArgumentException("The " + name + " block goes with a view from 0 up to " + view
				+ ", and no block with -1, found " + blockView);
		}

		if (block != null && block.getHeight() != height) {
			throw new IllegalArgumentException("The " + name + " block is of height " + block.getHeight()
				+ ", not " + height);
		}
	}

	private static void requireOneHeight(long height, List<Proposal> proposals, List<Ballot> ballots) {
		if (proposals.stream().anyMatch(proposal -> proposal.height() != height)
			|| ballots.stream().anyMatch(ballot -> ballot.height() != height)) {
			throw new IllegalArgumentException("Every proposal and ballot is of height " + height);
		}
	}

	private static void requireOnePerView(List<Proposal> proposals, List<Ballot> ballots) {
		if (proposals.stream().mapToInt(Proposal::view).distinct().count() != proposals.size()) {
			throw new IllegalArgumentException("A node signs one proposal in a view");
		}

		if (ballots.stream().map(ballot -> List.of(ballot.type(), ballot.view())).distinct().count()
			!= ballots.size()) {
			throw new IllegalArgumentException("A node signs one ballot of each type in a view");
		}
	}

	private static void requireWhatOneNodeCanSign(int view, int proposals, int ballots) {
		if (proposals > view + 1L || ballots > 2 * (view + 1L)) {
			throw new IllegalArgumentException("A state of view " + view + " holds at most " + (view + 1L)
				+ " proposals and " + 2 * (view + 1L) + " ballots, found " + proposals + " and " + ballots);
		}
	}

	private static byte flag(boolean value) {
		return value ? YES : NO;
	}

	private static byte[] encodeBlock(Block block) {
		if (block == null) {
			return new byte[] { NO };
		}

		final byte[] encoded = BlockCodec.encode(block);

		return Bytes.concat(new byte[] { YES }, Bytes.intBytes(encoded.length), encoded);
	}

	private static byte[] encodeMessages(List<? extends Message> messages) {
		final byte[] encoded = Bytes.concat(messages.stream().map(ConsensusCodec::encode).toArray(byte[][]::new));

		return Bytes.concat(Bytes.intBytes(messages.size()), encoded);
	}

	private static boolean decodeFlag(byte flag) {
		return switch (flag) {
			case NO -> false;
			case YES -> true;
			default -> throw new IllegalArgumentException("A flag is 0 or 1, found " + Byte.toUnsignedInt(flag));
		};
	}

	private static int decodeView(int view) {
		if (view < 0) {
			throw new IllegalArgumentException("A view is 0 or more, found " + view);
		}

		return view;
	}

	private static Consensus.Step decodeStep(byte ordinal) {
		final Consensus.Step[] steps = Consensus.Step.values();

		if (Byte.toUnsignedInt(ordinal) >= steps.length) {
			throw new IllegalArgumentException("Unknown step " + Byte.toUnsignedInt(ordinal));
		}

		return steps[ordinal];
	}

	private static Block decodeBlock(ByteBuffer in) {
		if (!decodeFlag(Bytes.take(in, 1)[0])) {
			return null;
		}

		final int length = Bytes.takeInt(in);

		if (length < 1 || length > BlockCodec.MAX_ENCODED_SIZE) {
			throw new IllegalArgumentException("A block is 1 to " + BlockCodec.MAX_ENCODED_SIZE
				+ " bytes, found " + length);
		}

		return BlockCodec.decode(Bytes.take(in, length));
	}

	private static <T extends Message> List<T> decodeMessages(ByteBuffer in, long most, int largestProof,
		Class<T> type) {
		final int count = Bytes.takeInt(in);

		if (count < 0 || count > most) {
			throw new IllegalArgumentException("A state of this view holds at most " + most + " messages of the "
				+ "kind " + type.getSimpleName() + ", found " + count);
		}

		final List<T> messages = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			final Message message = ConsensusCodec.decode(in, largestProof);

			if (!type.isInstance(message)) {
				throw new IllegalArgumentException("Expected a " + type.getSimpleName() + ", found "
					+ message.getClass().getSimpleName());
			}

			messages.add(type.cast(message));
		}

		return messages;
	}
}
