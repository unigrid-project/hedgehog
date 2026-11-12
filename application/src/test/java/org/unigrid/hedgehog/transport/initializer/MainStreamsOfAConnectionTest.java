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

package org.unigrid.hedgehog.transport.initializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.QuicPeer;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.server.BaseDefaultNodeSporkTest;

/* A node run without --preview takes every stream that a peer opens for a main stream (StreamRouter). A main stream has
   a pipeline of its own, the schedules of ping, peers and sporks, and every spork that counts written to it before a
   byte is read, and a peer that dials the node needs only the one. So the node serves a connection the first, closes
   a further one with nothing set up for it, and closes the connection of a peer that goes on opening them, over real
   QUIC to a node that is up. */
public class MainStreamsOfAConnectionTest extends BaseDefaultNodeSporkTest {
	private static final Duration PATIENCE = Duration.ofSeconds(5);
	private static final int HEADER_BYTES = 8;

	/* The further main stream that the node closes the connection with, written out here and not taken from the
	   router: the ones before it are closed alone */
	private static final int CLOSES_THE_CONNECTION = 3;

	/* A stream of the peer, and the pings it sent on it. Two, as an answer to the second says that the node has set the
	   stream up and run the schedule that is due on creation, which it does as the loop is next free. A node that
	   closes the stream says that it has read everything before it by the closing. */
	private record Opened(QuicPeer.Stream stream, long first, long second) {
		private static Opened on(final QuicPeer peer) {
			final QuicPeer.Stream stream = peer.open();
			final long first = stream.ping();

			stream.awaitAnswerOrClose(first);

			final long second = stream.ping();

			stream.awaitAnswerOrClose(second);
			return new Opened(stream, first, second);
		}

		private List<QuicPeer.Frame> frames() {
			return stream.frames();
		}

		private long framesOf(final Packet.Type type) {
			return frames().stream().filter(frame -> frame.type() == type).count();
		}

		private long bytesOf(final Packet.Type type) {
			return frames().stream().filter(frame -> frame.type() == type)
				.mapToLong(frame -> HEADER_BYTES + frame.body().length).sum();
		}

		/* The pings that the node sent of its own: the others on the stream are its answers to those of the peer */
		private long pingsOfTheNode() {
			return frames().stream().filter(frame -> frame.type() == Packet.Type.PING
				&& !frame.isAnswerToPing(first) && !frame.isAnswerToPing(second)).count();
		}

		private void assertStillAnswers() {
			stream.awaitAnswer(stream.ping());
		}

		private void assertClosedWithNothingWritten(final String name) {
			assertThat(name + " is open", stream.isOpen(), is(false));
			assertThat("frames the node wrote to " + name, frames(), is(empty()));
		}
	}

	/* The first stream and every further one up to the one that the node closes the connection with, which ends the
	   opening of streams */
	private static List<Opened> openAll(final QuicPeer peer) {
		final List<Opened> opened = new ArrayList<>();

		for (int n = 0; n <= CLOSES_THE_CONNECTION; n++) {
			opened.add(Opened.on(peer));
		}

		return opened;
	}

	private static QuicPeer dial() {
		return QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), Network.getProtocols());
	}

	private static long sum(final List<Long> counts) {
		return counts.stream().mapToLong(Long::longValue).sum();
	}

	/* The node holds a spork that counts, as one it sends to a peer that opens a stream */
	@Example
	public void writesTheSporksOnceToAConnectionWhateverNumberOfMainStreamsItsPeerOpens() throws InterruptedException {
		database.set(mintSupply());
		assertThat(Type.MINT_SUPPLY + " counting", database.counting(Type.MINT_SUPPLY), is(notNullValue()));

		try (QuicPeer peer = dial()) {
			final List<Opened> opened = openAll(peer);
			final List<Long> frames = opened.stream().map(o -> o.framesOf(Packet.Type.PUBLISH_SPORK)).toList();
			final long bytes = opened.stream().mapToLong(o -> o.bytesOf(Packet.Type.PUBLISH_SPORK)).sum();

			assertThat("spork frames the node wrote to the first stream", frames.getFirst(), is(greaterThan(0L)));
			assertThat("spork frames on each of the " + opened.size() + " main streams opened on one connection ("
				+ frames + ", " + bytes + " bytes in all, " + opened.getFirst().bytesOf(Packet.Type.PUBLISH_SPORK)
				+ " on the first)", sum(frames), is(lessThanOrEqualTo(frames.getFirst())));
		}
	}

	/* The ping is the schedule that is due at once on a stream. The pipeline of 15 handlers and the 3 schedules that a
	   stream is given are counted off the pipeline of the node itself. */
	@Example
	public void schedulesAConnectionOnceWhateverNumberOfMainStreamsItsPeerOpens() throws InterruptedException {
		try (QuicPeer peer = dial()) {
			final List<Opened> opened = openAll(peer);

			await("a ping of the node on the first stream").atMost(PATIENCE)
				.until(() -> opened.getFirst().pingsOfTheNode() > 0);

			final List<Long> pings = opened.stream().map(Opened::pingsOfTheNode).toList();
			final int handlers = PeerPipeline.handlers(RegisterQuicChannelInitializer.Type.SERVER, false, false).size();
			final int schedules = PeerPipeline.schedulers(false, false).size();

			assertThat("pings the node sent of its own on each of the " + opened.size() + " main streams opened on one "
				+ "connection (" + pings + "), where each stream has a pipeline of " + handlers + " handlers and "
				+ schedules + " schedules: " + opened.size() * handlers + " handlers and " + opened.size() * schedules
				+ " schedules for the one peer", sum(pings), is(lessThanOrEqualTo(pings.getFirst())));
		}
	}

	/* Not a frame reaches the further stream, neither a spork nor the answer to its pings, and the first goes on */
	@Example
	public void closesAFurtherMainStreamWithNothingWrittenToItAndKeepsTheFirst() throws InterruptedException {
		database.set(mintSupply());

		try (QuicPeer peer = dial()) {
			final Opened first = Opened.on(peer);

			Opened.on(peer).assertClosedWithNothingWritten("the further stream");

			assertThat("the connection is open", peer.connection().isActive(), is(true));
			assertThat("the first stream is open", first.stream().isOpen(), is(true));
			first.assertStillAnswers();
		}
	}

	/* The one main stream is for the life of the connection: a peer that ended it gets no other in its place */
	@Example
	public void servesNoMainStreamInPlaceOfTheFirstOnceThatHasEnded() throws InterruptedException {
		database.set(mintSupply());

		try (QuicPeer peer = dial()) {
			final Opened first = Opened.on(peer);

			assertThat("spork frames the node wrote to the first stream", first.framesOf(Packet.Type.PUBLISH_SPORK),
				is(greaterThan(0L)));
			first.stream().close();

			Opened.on(peer).assertClosedWithNothingWritten("the stream opened after the first ended");
		}
	}

	/* A peer has no use for a further main stream, so one that opens them again and again is after the node: the node
	   lets it off with the first ones, which it only closes, and then closes the connection, the first stream with it */
	@Example
	public void closesTheConnectionOfAPeerThatGoesOnOpeningMainStreams() throws InterruptedException {
		try (QuicPeer peer = dial()) {
			final Opened first = Opened.on(peer);

			for (int further = 1; further < CLOSES_THE_CONNECTION; further++) {
				Opened.on(peer).assertClosedWithNothingWritten("further stream " + further);

				assertThat("the connection is open after further stream " + further, peer.connection().isActive(),
					is(true));
				first.assertStillAnswers();
			}

			Opened.on(peer).assertClosedWithNothingWritten("further stream " + CLOSES_THE_CONNECTION);

			await("the connection to be closed with further stream " + CLOSES_THE_CONNECTION).atMost(PATIENCE)
				.until(() -> !peer.connection().isActive());
			assertThat("the first stream is open", first.stream().isOpen(), is(false));
		}
	}
}
