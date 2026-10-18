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

package org.unigrid.hedgehog.s3.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class S3RequestBodyTest {
	private static final int HIGH_WATER = 1024;
	private static final int LOW_WATER = 256;
	private static final Duration STALL = Duration.ofMillis(200);

	private static S3RequestBody body(final EmbeddedChannel channel) {
		return new S3RequestBody(channel, new S3ReadGate(channel), HIGH_WATER, LOW_WATER, STALL);
	}

	private static List<ByteBuf> offer(final S3RequestBody body, final List<byte[]> parts) {
		final List<ByteBuf> buffers = parts.stream().map(part -> Unpooled.buffer(part.length).writeBytes(part))
			.toList();

		buffers.forEach(body::offer);
		return buffers;
	}

	private static List<Integer> counts(final List<ByteBuf> buffers) {
		return buffers.stream().map(ByteBuf::refCnt).toList();
	}

	@Property
	public void readsWhatWasOfferedInOrderAndReleasesIt(@ForAll @Size(max = 8) List<@Size(max = 700) byte[]> parts,
		@ForAll @IntRange(min = 1, max = 300) int block) throws IOException {

		final S3RequestBody body = body(new EmbeddedChannel());
		final List<ByteBuf> buffers = offer(body, parts);
		final ByteArrayOutputStream expected = new ByteArrayOutputStream();
		final ByteArrayOutputStream read = new ByteArrayOutputStream();
		final byte[] buffer = new byte[block];

		parts.forEach(expected::writeBytes);
		body.end();

		for (int count = body.read(buffer); count >= 0; count = body.read(buffer)) {
			read.write(buffer, 0, count);
		}

		assertThat(read.toByteArray(), equalTo(expected.toByteArray()));
		assertThat(counts(buffers), everyItem(equalTo(0)));
	}

	@Example
	public void stopsReadingItsChannelAtTheHighMarkUntilDrainedBelowTheLowOne() throws IOException {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final S3RequestBody body = body(channel);

		offer(body, List.of(new byte[HIGH_WATER - 1]));
		assertThat(channel.config().isAutoRead(), is(true));

		offer(body, List.of(new byte[1]));
		assertThat(channel.config().isAutoRead(), is(false));

		body.readNBytes(HIGH_WATER - LOW_WATER - 1);
		assertThat(channel.config().isAutoRead(), is(false));
		assertThat(body.available(), equalTo(LOW_WATER + 1));

		body.readNBytes(1);
		assertThat(channel.config().isAutoRead(), is(true));
	}

	@Example
	public void failsItsReaderAndReleasesWhatItHeld() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final S3RequestBody body = body(channel);
		final List<ByteBuf> buffers = offer(body, List.of(new byte[HIGH_WATER], new byte[1]));

		body.fail(new ClosedChannelException());

		assertThat(Expect.assertThrows(IOException.class, body::read).getCause(),
			instanceOf(ClosedChannelException.class)
		);
		assertThat(counts(buffers), everyItem(equalTo(0)));
		assertThat(counts(offer(body, List.of(new byte[1]))), everyItem(equalTo(0)));
		assertThat(channel.config().isAutoRead(), is(true));
	}

	@Example
	public void stopsReadingOnceClosedUntilItsResponseDecidesToDrainTheRest() throws IOException {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final S3RequestBody body = body(channel);
		final List<ByteBuf> buffers = offer(body, List.of(new byte[1]));

		body.close();

		assertThat(body.read(), equalTo(-1));
		assertThat(counts(buffers), everyItem(equalTo(0)));
		assertThat(channel.config().isAutoRead(), is(false));

		body.discard(true);
		body.close();

		assertThat(channel.config().isAutoRead(), is(true));
		assertThat(counts(offer(body, List.of(new byte[1]))), everyItem(equalTo(0)));
	}

	@Example
	public void knowsWhetherTheRestOfItsBodyIsWithinALimit() {
		final S3RequestBody body = body(new EmbeddedChannel());

		offer(body, List.of(new byte[LOW_WATER]));

		assertThat(body.remainsWithin(LOW_WATER + 2, 2), is(true));
		assertThat(body.remainsWithin(LOW_WATER + 3, 2), is(false));
		assertThat(body.remainsWithin(-1, HIGH_WATER), is(false));

		body.end();

		assertThat(body.remainsWithin(-1, 0), is(true));
		body.discard(true);
	}

	@Example
	public void cutsAConnectionThatSendsNothingForTooLong() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final S3RequestBody body = body(channel);

		assertThat(Expect.assertThrows(IOException.class, body::read).getMessage(),
			equalTo("java.io.IOException: The request body stalled for " + STALL.toMillis() + " ms")
		);
		assertThat(channel.isOpen(), is(false));
	}
}
