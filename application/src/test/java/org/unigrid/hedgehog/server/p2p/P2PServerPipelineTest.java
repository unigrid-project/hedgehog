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

package org.unigrid.hedgehog.server.p2p;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.logging.LoggingHandler;
import java.math.BigDecimal;
import java.time.Instant;
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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import static org.unigrid.hedgehog.model.network.PreviewFrames.frameOf;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.HelloDecoder;
import org.unigrid.hedgehog.model.network.codec.PingDecoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishPeersEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.transport.handler.HelloChannelHandler;
import org.unigrid.hedgehog.transport.handler.PeerActivityHandler;
import org.unigrid.hedgehog.transport.handler.PingChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishGridnodeChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishPeersChannelHandler;
import org.unigrid.hedgehog.transport.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.transport.handler.UnclaimedFrameHandler;
import org.unigrid.hedgehog.transport.initializer.LedgerPipeline;
import org.unigrid.hedgehog.transport.initializer.StoragePipeline;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.transport.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.transport.schedule.PublishGridnodeSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;

public class P2PServerPipelineTest {
	private static final long SIGNED_AT = 1_700_000_000_000L;
	private static final int SIGNATURE_BYTES = 139;

	/* Whether a node offers the ledger protocol and the proxy protocol: it offers the first only with the second */
	private record Offer(boolean ledger, boolean preview) {
		private List<ChannelHandler> handlers() {
			return P2PServer.handlers(ledger, preview);
		}
	}

	private static final List<Offer> OFFERS = List.of(new Offer(false, false), new Offer(false, true),
		new Offer(true, true));

	private static final List<Class<?>> HEAD = List.of(PeerActivityHandler.class, LoggingHandler.class,
		FrameDecoder.class, HelloDecoder.class);

	private static final List<Class<?>> GRIDNODE_CODECS = List.of(GridnodeEncoder.class, GridnodeDecoder.class);

	private static final List<Class<?>> CORE = List.of(PingEncoder.class, PingDecoder.class,
		PublishSporkEncoder.class, PublishSporkDecoder.class, PublishPeersEncoder.class, PublishPeersDecoder.class,
		PingChannelHandler.class, PublishSporkChannelHandler.class, HelloChannelHandler.class,
		PublishPeersChannelHandler.class);

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

	private static GridSpork mintSupply() {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		spork.setPreviousData(data);
		spork.setTimeStamp(Instant.ofEpochMilli(SIGNED_AT));
		spork.setPreviousTimeStamp(Instant.ofEpochMilli(SIGNED_AT));
		spork.setSignature(new byte[SIGNATURE_BYTES]);
		return spork;
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

		assertThat(classesOf(P2PServer.handlers(false, false)), equalTo(joined(List.of(HEAD, CORE, DROP))));
		assertThat(classesOf(P2PServer.handlers(false, true)),
			equalTo(joined(List.of(HEAD, GRIDNODE_CODECS, CORE, GRIDNODE_STAGE, storage, DROP))));
		assertThat(classesOf(P2PServer.handlers(true, false)), equalTo(joined(List.of(HEAD, CORE, ledger, DROP))));
		assertThat(classesOf(P2PServer.handlers(true, true)),
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
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(true, true));
		final List<Class<?>> appended = classesOf(Stream.concat(StoragePipeline.handlers().stream(),
			LedgerPipeline.handlers().stream()).toList());

		assertThat(Collections.indexOfSubList(handlers, appended), equalTo(handlers.size() - appended.size() - 1));
	}

	@Example
	public void endsWithTheStoragePipelineBeforeTheDropAndHasNoLedgerStageWithoutTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(false, true));
		final List<Class<?>> storage = classesOf(StoragePipeline.handlers());

		assertThat(Collections.indexOfSubList(handlers, storage), equalTo(handlers.size() - storage.size() - 1));
		classesOf(LedgerPipeline.handlers()).stream().filter(type -> !storage.contains(type))
			.forEach(type -> assertThat(handlers, not(hasItem(type))));
	}

	@Example
	public void carriesTheGridnodeAndStorageStagesWithTheProxyProtocol() {
		assertThat(classesOf(P2PServer.handlers(false, true)), hasItems(previewStages().toArray(new Class<?>[0])));
	}

	/* A node without the preview keeps every other stage where it is, those of the sporks, peers and pings */
	@Example
	public void leavesOutOnlyTheGridnodeAndStorageStagesWithoutTheProxyProtocol() {
		final Set<Class<?>> left = previewStages();
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(false, false));

		left.forEach(type -> assertThat(handlers, not(hasItem(type))));
		assertThat(handlers, equalTo(classesOf(P2PServer.handlers(false, true)).stream()
			.filter(type -> !left.contains(type)).toList()));
	}

	/* A node without the gridnode stages still hears the sporks of peers that announce gridnodes. The stage that
	   takes sporks needs a running node, so the spork is read off the end of the stream instead. */
	@Example
	public void readsTheSporkAfterAGridnodeFrameOnANodeWithoutTheGridnodeStages() {
		final EmbeddedChannel stream = new EmbeddedChannel(P2PServer.handlers(false, false).stream()
			.filter(handler -> !(handler instanceof PublishSporkChannelHandler)).toArray(ChannelHandler[]::new));
		final GridSpork spork = mintSupply();

		stream.writeInbound(PreviewFrames.gridnode());
		stream.writeInbound(frameOf(new PublishSporkEncoder(), PublishSpork.builder().gridSpork(spork).build()));

		final Object read = stream.readInbound();

		assertThat(read, instanceOf(PublishSpork.class));
		assertThat(((PublishSpork) read).getGridSpork(), equalTo(spork));
		assertThat(stream.readInbound(), is(nullValue()));
		assertThat(stream.isOpen(), is(true));
	}

	/* A peer run with the preview announces gridnodes and sends storage packets, and a node without it drops each
	   of them whole and goes on: the ping that comes next, like the one that opens a stream, is answered */
	@Example
	public void dropsWhatAPeerWithThePreviewSendsForItsPartsAndAnswersAPingAfterIt() {
		final EmbeddedChannel stream = new EmbeddedChannel(P2PServer.handlers(false, false)
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
		final List<Class<?>> earlier = P2PServer.schedulers(false, true).stream().<Class<?>>map(Object::getClass)
			.toList();
		final List<Class<?>> later = P2PServer.schedulers(true, true).stream().<Class<?>>map(Object::getClass)
			.toList();

		assertThat(earlier, not(hasItem(LedgerStatusSchedule.class)));
		assertThat(later, hasItems(earlier.toArray(new Class<?>[0])));
		assertThat(later, hasItem(LedgerStatusSchedule.class));
		assertThat(later.size(), equalTo(earlier.size() + 1));
	}

	@Example
	public void announcesGridnodesOnlyWithTheProxyProtocol() {
		final List<Class<?>> release = P2PServer.schedulers(false, false).stream().<Class<?>>map(Object::getClass)
			.toList();
		final List<Class<?>> preview = P2PServer.schedulers(false, true).stream().<Class<?>>map(Object::getClass)
			.toList();

		assertThat(release, not(hasItem(PublishGridnodeSchedule.class)));
		assertThat(preview, hasItem(PublishGridnodeSchedule.class));
		assertThat(release, equalTo(preview.stream().filter(type -> type != PublishGridnodeSchedule.class).toList()));
	}
}
