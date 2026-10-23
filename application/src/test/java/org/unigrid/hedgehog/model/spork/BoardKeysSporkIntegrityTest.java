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

package org.unigrid.hedgehog.model.spork;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeys.SporkData;
import org.unigrid.hedgehog.model.spork.KeyChange.Approval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class BoardKeysSporkIntegrityTest {
	private static final int KEY_LENGTH = 2 * Signature.PUBLIC_KEY_HEX_SIZE;

	/* Changes that can travel, whether or not they hold as a chain */
	static SporkData chainOf(Random random, int changes, int keys, int approvals) {
		final SporkData data = new SporkData();

		for (int i = 0; i < changes; i++) {
			final List<String> active = new ArrayList<>(IntStream.range(0, keys).mapToObj(n -> key(random))
				.sorted().toList());
			final List<Approval> approved = new ArrayList<>(IntStream.range(0, approvals)
				.mapToObj(n -> new Approval(key(random), bytes(random, 130 + random.nextInt(10)))).toList());

			data.getChanges().add(new KeyChange(i, Instant.ofEpochMilli(random.nextLong(0, 1L << 42)),
				bytes(random, KeyChange.DIGEST_SIZE), active, approved));
		}

		return data;
	}

	private static String key(Random random) {
		return HexFormat.of().formatHex(bytes(random, Signature.PUBLIC_KEY_HEX_SIZE));
	}

	private static byte[] bytes(Random random, int size) {
		final byte[] bytes = new byte[size];

		random.nextBytes(bytes);
		return bytes;
	}

	@SneakyThrows
	private static ByteBuf encoded(SporkData data) {
		final ByteBuf buffer = Unpooled.buffer();

		new BoardKeysEncoder().encodeChunk(null, data, buffer);
		return buffer;
	}

	@SneakyThrows
	private static SporkData decoded(ByteBuf buffer) {
		return new BoardKeysDecoder().decodeChunk(null, buffer).orElseThrow();
	}

	@Property(tries = 50)
	public void survivesARoundTrip(@ForAll long seed) {
		final Random random = new Random(seed);
		final SporkData data = chainOf(random, random.nextInt(5), 2 + random.nextInt(15), random.nextInt(33));
		final ByteBuf buffer = encoded(data);

		assertThat(decoded(buffer), equalTo(data));
		assertThat(buffer.readableBytes(), equalTo(0));
	}

	@Example
	public void writesEachChangeWithItsApprovals() {
		final SporkData data = chainOf(new Random(1), 2, 3, 2);
		final ByteBuf buffer = encoded(data);
		final byte[] first = data.getChanges().get(0).toBytes();
		final byte[] written = new byte[first.length];

		assertThat(buffer.readUnsignedByte(), equalTo((short) 2));
		buffer.readBytes(written);
		assertThat(written, equalTo(first));
	}

	@Example
	public void refusesMoreChangesKeysOrApprovalsThanTheCapsOnTheWire() {
		final ByteBuf changes = Unpooled.buffer().writeByte(BoardKeyChain.MAX_CHANGES + 1);
		final ByteBuf keys = encoded(chainOf(new Random(2), 1, 2, 0));
		final ByteBuf approvals = encoded(chainOf(new Random(3), 1, 2, 0));

		keys.setByte(1 + Integer.BYTES + Long.BYTES + KeyChange.DIGEST_SIZE, BoardKeyChain.MAX_KEYS + 1);
		approvals.setByte(approvals.writerIndex() - 1, BoardKeyChain.MAX_APPROVALS + 1);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(changes)).getMessage(),
			startsWith("A chain of 65 changes of the board keys"));
		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(keys)).getMessage(),
			startsWith("A change of the board keys with 17 keys"));
		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(approvals)).getMessage(),
			startsWith("A change of the board keys with 33 approvals"));
	}

	@Example
	public void refusesAKeyOrASignatureLongerThanAnyOnTheWire() {
		final ByteBuf key = encoded(chainOf(new Random(4), 1, 2, 0));
		final SporkData approved = chainOf(new Random(5), 1, 2, 1);
		final ByteBuf signature = encoded(approved);
		final int signatureSize = approved.getChanges().get(0).getApprovals().get(0).getSignature().length;
		final int firstKey = 1 + Integer.BYTES + Long.BYTES + KeyChange.DIGEST_SIZE + 1;

		key.setShort(firstKey, KEY_LENGTH + 1);
		signature.setShort(signature.writerIndex() - signatureSize - Short.BYTES, KeyChange.MAX_SIGNATURE_SIZE + 1);

		assertThat(assertThrows(CorruptedFrameException.class, () -> decoded(key)).getMessage(),
			startsWith("A key of 263 characters"));
		assertThrows(CorruptedFrameException.class, () -> decoded(signature));
	}

	@Example
	public void refusesToWriteWhatItsPeersWouldNotAccept() {
		final SporkData tooLong = chainOf(new Random(6), BoardKeyChain.MAX_CHANGES + 1, 2, 0);
		final SporkData upperCase = chainOf(new Random(7), 1, 2, 0);
		final SporkData shortDigest = chainOf(new Random(8), 1, 2, 0);
		final SporkData none = new SporkData();

		upperCase.getChanges().get(0).getActive().set(0, key(new Random(9)).toUpperCase());
		shortDigest.getChanges().get(0).setPrevious(new byte[KeyChange.DIGEST_SIZE - 1]);
		none.setChanges(null);

		assertThrows(IllegalArgumentException.class, () -> encoded(tooLong));
		assertThrows(IllegalArgumentException.class, () -> encoded(upperCase));
		assertThrows(IllegalArgumentException.class, () -> encoded(shortDigest));
		assertThrows(IllegalArgumentException.class, () -> encoded(none));
	}
}
