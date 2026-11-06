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

package org.unigrid.hedgehog.client.p2p;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LoggingHandler;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.HelloEncoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.handler.PeerActivityHandler;
import org.unigrid.hedgehog.model.network.handler.PingChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishGridnodeChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishPeersChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.handler.UnclaimedFrameHandler;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.initializer.StoragePipeline;
import org.unigrid.hedgehog.model.network.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.model.network.schedule.PublishGridnodeSchedule;

public class P2PClientPipelineTest {
	/* Whether a node offers the ledger protocol and the proxy protocol: it offers the first only with the second */
	private record Offer(boolean ledger, boolean preview) {
		private List<ChannelHandler> handlers() {
			return P2PClient.handlers(ledger, preview);
		}
	}

	private static final List<Offer> OFFERS = List.of(new Offer(false, false), new Offer(false, true),
		new Offer(true, true));

	private static final List<Class<?>> HEAD = List.of(PeerActivityHandler.class, LoggingHandler.class,
		FrameDecoder.class, HelloEncoder.class);

	private static final List<Class<?>> GRIDNODE_CODECS = List.of(GridnodeEncoder.class, GridnodeDecoder.class);

	private static final List<Class<?>> CORE = List.of(PingEncoder.class, PingDecoder.class,
		PublishSporkEncoder.class, PublishSporkDecoder.class, PublishPeersEncoder.class, PublishPeersDecoder.class,
		PingChannelHandler.class, PublishSporkChannelHandler.class, PublishPeersChannelHandler.class);

	private static final List<Class<?>> GRIDNODE_STAGE = List.of(PublishGridnodeChannelHandler.class);
	private static final List<Class<?>> DROP = List.of(UnclaimedFrameHandler.class);

	private static List<Class<?>> classesOf(final List<? extends ChannelHandler> handlers) {
		return handlers.stream().<Class<?>>map(ChannelHandler::getClass).toList();
	}

	private static List<Class<?>> joined(final List<List<Class<?>>> parts) {
		return parts.stream().flatMap(List::stream).toList();
	}

	/* The stages of the parts of the node that only the preview turns on */
	private static Set<Class<?>> previewStages() {
		return Stream.concat(Stream.of(GridnodeEncoder.class, GridnodeDecoder.class,
			PublishGridnodeChannelHandler.class), classesOf(StoragePipeline.handlers()).stream())
			.collect(Collectors.toSet());
	}

	/* Every read shows the peer is there, also one of a piece of a frame that is still streaming in */
	@Example
	public void takesEachReadAsASignOfThePeerBeforeAnyFrameIsDecoded() {
		for (Offer offer : OFFERS) {
			assertThat(classesOf(offer.handlers()).getFirst(), equalTo(PeerActivityHandler.class));
		}
	}

	/* Behind every decoder, so that a frame no stage takes does not run into the frames after it */
	@Example
	public void dropsTheFramesNoStageTakesLast() {
		for (Offer offer : OFFERS) {
			assertThat(classesOf(offer.handlers()).getLast(), equalTo(UnclaimedFrameHandler.class));
		}
	}

	@Example
	public void buildsEveryStageInItsPlaceForEachMixOfTheProtocols() {
		final List<Class<?>> storage = classesOf(StoragePipeline.handlers());
		final List<Class<?>> ledger = classesOf(LedgerPipeline.handlers());

		assertThat(classesOf(P2PClient.handlers(false, false)), equalTo(joined(List.of(HEAD, CORE, DROP))));
		assertThat(classesOf(P2PClient.handlers(false, true)),
			equalTo(joined(List.of(HEAD, GRIDNODE_CODECS, CORE, GRIDNODE_STAGE, storage, DROP))));
		assertThat(classesOf(P2PClient.handlers(true, false)), equalTo(joined(List.of(HEAD, CORE, ledger, DROP))));
		assertThat(classesOf(P2PClient.handlers(true, true)),
			equalTo(joined(List.of(HEAD, GRIDNODE_CODECS, CORE, GRIDNODE_STAGE, storage, ledger, DROP))));
	}

	/* The decoders keep state, so a stream never shares a stage with another one */
	@Example
	public void buildsFreshStagesForEachStream() {
		for (Offer offer : OFFERS) {
			final Set<ChannelHandler> earlier = Collections.newSetFromMap(new IdentityHashMap<>());

			earlier.addAll(offer.handlers());
			offer.handlers().forEach(handler -> assertThat(earlier.contains(handler), is(false)));
		}
	}

	@Example
	public void endsWithTheStoragePipelineAndThenTheLedgerPipelineBeforeTheDropWithTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PClient.handlers(true, true));
		final List<Class<?>> appended = classesOf(Stream.concat(StoragePipeline.handlers().stream(),
			LedgerPipeline.handlers().stream()).toList());

		assertThat(Collections.indexOfSubList(handlers, appended), equalTo(handlers.size() - appended.size() - 1));
	}

	@Example
	public void endsWithTheStoragePipelineBeforeTheDropAndHasNoLedgerStageWithoutTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PClient.handlers(false, true));
		final List<Class<?>> storage = classesOf(StoragePipeline.handlers());

		assertThat(Collections.indexOfSubList(handlers, storage), equalTo(handlers.size() - storage.size() - 1));
		classesOf(LedgerPipeline.handlers()).stream().filter(type -> !storage.contains(type))
			.forEach(type -> assertThat(handlers, not(hasItem(type))));
	}

	@Example
	public void carriesTheGridnodeAndStorageStagesWithTheProxyProtocol() {
		assertThat(classesOf(P2PClient.handlers(false, true)), hasItems(previewStages().toArray(new Class<?>[0])));
	}

	/* A node without the preview keeps every other stage where it is, those of the sporks, peers and pings */
	@Example
	public void leavesOutOnlyTheGridnodeAndStorageStagesWithoutTheProxyProtocol() {
		final Set<Class<?>> left = previewStages();
		final List<Class<?>> handlers = classesOf(P2PClient.handlers(false, false));

		left.forEach(type -> assertThat(handlers, not(hasItem(type))));
		assertThat(handlers, equalTo(classesOf(P2PClient.handlers(false, true)).stream()
			.filter(type -> !left.contains(type)).toList()));
	}

	/* A peer run with the preview announces gridnodes and sends storage packets, and a node without it drops each
	   of them whole and goes on: the ping that comes next is answered */
	@Example
	public void dropsWhatAPeerWithThePreviewSendsForItsPartsAndAnswersAPingAfterIt() {
		final EmbeddedChannel stream = new EmbeddedChannel(P2PClient.handlers(false, false)
			.toArray(ChannelHandler[]::new));

		for (ByteBuf frame : PreviewFrames.ofTheParts()) {
			assertThat(stream.writeInbound(frame), is(false));
			assertThat(frame.refCnt(), is(0));
			assertThat(stream.isOpen(), is(true));
		}

		assertThat(stream.readOutbound(), is(nullValue()));
		assertThat(stream.writeInbound(PreviewFrames.ping()), is(false));
		PreviewFrames.assertThePingIsAnsweredAlone(stream);
		assertThat(stream.isOpen(), is(true));
	}

	@Example
	public void runsTheLedgerScheduleOnlyWithTheLedgerProtocol() {
		final List<Class<?>> earlier = P2PClient.schedulers(false, true).stream().<Class<?>>map(Object::getClass)
			.toList();
		final List<Class<?>> later = P2PClient.schedulers(true, true).stream().<Class<?>>map(Object::getClass)
			.toList();

		assertThat(earlier, not(hasItem(LedgerStatusSchedule.class)));
		assertThat(later, hasItems(earlier.toArray(new Class<?>[0])));
		assertThat(later, hasItem(LedgerStatusSchedule.class));
		assertThat(later.size(), equalTo(earlier.size() + 1));
	}

	@Example
	public void announcesGridnodesOnlyWithTheProxyProtocol() {
		final List<Class<?>> release = P2PClient.schedulers(false, false).stream().<Class<?>>map(Object::getClass)
			.toList();
		final List<Class<?>> preview = P2PClient.schedulers(false, true).stream().<Class<?>>map(Object::getClass)
			.toList();

		assertThat(release, not(hasItem(PublishGridnodeSchedule.class)));
		assertThat(preview, hasItem(PublishGridnodeSchedule.class));
		assertThat(release, equalTo(preview.stream().filter(type -> type != PublishGridnodeSchedule.class).toList()));
	}
}
