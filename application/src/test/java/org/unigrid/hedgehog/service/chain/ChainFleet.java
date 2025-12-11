/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* Gridnodes that share one clock, one topology and one set of settings, each with its own host. A stream
   between two hosts is an embedded channel at each end. */
final class ChainFleet {
	static final long NOW = 1_750_000_000_000L;

	/* Each end of a stream is a channel of its own, so the key tells whose end it is */
	private record Link(String owner, String peer) {
	}

	final AtomicLong clock = new AtomicLong(NOW);
	final AtomicReference<Optional<ProxySettings>> settings = new AtomicReference<>(Optional.of(ProxySettings.DEFAULTS));
	final List<Gridnode> gridnodes = new CopyOnWriteArrayList<>();
	private final Map<String, ChainHost> hosts = new LinkedHashMap<>();
	private final Map<Link, EmbeddedChannel> streams = new HashMap<>();
	private final Set<String> offline = new HashSet<>();

	ChainFleet(final int size) {
		IntStream.range(0, size).forEach(number -> gridnodes.add(gridnode("gn-" + number, Gridnode.Status.ACTIVE)));
	}

	static Gridnode gridnode(final String id, final Gridnode.Status status) {
		return Gridnode.builder().id(id).status(status).hostName(id + ":1").build();
	}

	static ProxySettings settings(final int groupSize, final int chainsPerGroup, final int clientsPerChain,
		final int retentionSeconds, final int flags) {

		return new ProxySettings(groupSize, 3600, chainsPerGroup, clientsPerChain, 32L * 1024 * 1024,
			retentionSeconds, 16 * 1024, 300, flags);
	}

	static WriterKey key(final int number) {
		return new WriterKey(ByteBuffer.allocate(WriterKey.SEED_SIZE).putInt(number).array());
	}

	static WriterId writer(final int number) {
		return key(number).id();
	}

	/* The first entries of one writer's feed, each timestamped at the given time */
	static List<Entry> entries(final WriterKey key, final ChainId chainId, final int count, final long timestamp) {
		final FeedWriter feed = new FeedWriter(key, chainId);

		return IntStream.range(0, count).mapToObj(number -> feed.write(timestamp, Fingerprint.NONE, EntryKind.SEALED,
			new byte[] { (byte) number })).toList();
	}

	ChainHost host(final String id) {
		return hosts.computeIfAbsent(id, own -> hostOver(own, peer -> streamBetween(own, peer)));
	}

	/* A host that is not one of the fleet, for a transport the test supplies */
	ChainHost hostOver(final String id, final ChainTransport transport) {
		return new ChainHost(settings::get, new TopologyGridnodeDirectory(() -> gridnodes, () -> id), transport,
			clock::get);
	}

	/* An offline host gets no stream from the others until it is back */
	void offline(final String id) {
		offline.add(id);
	}

	void online(final String id) {
		offline.remove(id);
	}

	EmbeddedChannel stream(final String owner, final String peer) {
		return streams.computeIfAbsent(new Link(owner, peer), link -> new EmbeddedChannel());
	}

	private Optional<Channel> streamBetween(final String owner, final String peer) {
		return hosts.containsKey(peer) && !offline.contains(peer) ? Optional.of(stream(owner, peer)) : Optional.empty();
	}

	List<String> activeIds() {
		return gridnodes.stream().filter(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE)
			.map(Gridnode::getId).toList();
	}

	GroupView view() {
		return new GroupView(settings.get().orElseThrow(), clock.get(), activeIds());
	}

	void refreshAll() {
		new ArrayList<>(hosts.values()).forEach(ChainHost::refresh);
	}
}
