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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeFixtures;
import org.unigrid.hedgehog.model.network.codec.GridnodeDecoder;
import org.unigrid.hedgehog.model.network.codec.GridnodeEncoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.handler.PeerActivityHandler;
import org.unigrid.hedgehog.model.network.handler.PublishGridnodeChannelHandler;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.handler.UnclaimedFrameHandler;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.initializer.StoragePipeline;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.network.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;

public class P2PServerPipelineTest {
	private static final long SIGNED_AT = 1_700_000_000_000L;
	private static final int SIGNATURE_BYTES = 139;

	/* The stages a node without gridnodes leaves out, and the one that takes sporks, which needs a running node: the
	   spork is read off the end of the stream instead */
	private static final Set<Class<?>> LEFT_OUT = Set.of(GridnodeEncoder.class, GridnodeDecoder.class,
		PublishGridnodeChannelHandler.class, PublishSporkChannelHandler.class);

	private static List<Class<?>> classesOf(final List<? extends ChannelHandler> handlers) {
		return handlers.stream().<Class<?>>map(ChannelHandler::getClass).toList();
	}

	private static ByteBuf frameOf(final ChannelHandler encoder, final Object packet) {
		final EmbeddedChannel encoding = new EmbeddedChannel(encoder);

		encoding.writeOutbound(packet);
		return encoding.readOutbound();
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
		for (boolean ledger : List.of(false, true)) {
			assertThat(classesOf(P2PServer.handlers(ledger)).getFirst(), equalTo(PeerActivityHandler.class));
		}
	}

	/* Behind every decoder, so that a frame no stage takes does not run into the frames after it */
	@Example
	public void dropsTheFramesNoStageTakesLast() {
		for (boolean ledger : List.of(false, true)) {
			assertThat(classesOf(P2PServer.handlers(ledger)).getLast(), equalTo(UnclaimedFrameHandler.class));
		}
	}

	@Example
	public void endsWithTheStoragePipelineAndThenTheLedgerPipelineBeforeTheDropWithTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(true));
		final List<Class<?>> appended = classesOf(Stream.concat(StoragePipeline.handlers().stream(),
			LedgerPipeline.handlers().stream()).toList());

		assertThat(Collections.indexOfSubList(handlers, appended), equalTo(handlers.size() - appended.size() - 1));
	}

	@Example
	public void endsWithTheStoragePipelineBeforeTheDropAndHasNoLedgerStageWithoutTheLedgerProtocol() {
		final List<Class<?>> handlers = classesOf(P2PServer.handlers(false));
		final List<Class<?>> storage = classesOf(StoragePipeline.handlers());

		assertThat(Collections.indexOfSubList(handlers, storage), equalTo(handlers.size() - storage.size() - 1));
		classesOf(LedgerPipeline.handlers()).stream().filter(type -> !storage.contains(type))
			.forEach(type -> assertThat(handlers, not(hasItem(type))));
	}

	/* A node without the gridnode stages still hears the announcements of peers that have them */
	@Example
	public void readsTheSporkAfterAGridnodeFrameOnANodeWithoutTheGridnodeStages() throws Exception {
		final EmbeddedChannel stream = new EmbeddedChannel(P2PServer.handlers(false).stream()
			.filter(handler -> !LEFT_OUT.contains(handler.getClass())).toArray(ChannelHandler[]::new));
		final Gridnode gridnode = GridnodeFixtures.signed(new Signature(), Gridnode.Status.ACTIVE,
			"localhost:52884", SIGNED_AT);
		final GridSpork spork = mintSupply();

		stream.writeInbound(frameOf(new GridnodeEncoder(), PublishGridnode.builder().gridnode(gridnode).build()));
		stream.writeInbound(frameOf(new PublishSporkEncoder(), PublishSpork.builder().gridSpork(spork).build()));

		final Object read = stream.readInbound();

		assertThat(read, instanceOf(PublishSpork.class));
		assertThat(((PublishSpork) read).getGridSpork(), equalTo(spork));
		assertThat(stream.readInbound(), is(nullValue()));
		assertThat(stream.isOpen(), is(true));
	}

	@Example
	public void runsTheLedgerScheduleOnlyWithTheLedgerProtocol() {
		final List<Class<?>> earlier = P2PServer.schedulers(false).stream().<Class<?>>map(Object::getClass).toList();
		final List<Class<?>> later = P2PServer.schedulers(true).stream().<Class<?>>map(Object::getClass).toList();

		assertThat(earlier, not(hasItem(LedgerStatusSchedule.class)));
		assertThat(later, hasItems(earlier.toArray(new Class<?>[0])));
		assertThat(later, hasItem(LedgerStatusSchedule.class));
		assertThat(later.size(), equalTo(earlier.size() + 1));
	}
}
