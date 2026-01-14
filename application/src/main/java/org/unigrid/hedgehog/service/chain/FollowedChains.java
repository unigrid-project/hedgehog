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
import io.netty.util.AttributeKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* The chains a node follows from outside their groups, as a client does: a replica of each, kept until it is
   unfollowed or the last stream it is followed through closes. A stream is watched once, however many chains it is
   followed for, and its close drops it from every replica. */
@RequiredArgsConstructor
final class FollowedChains {
	/* Set on a stream once its close is awaited, on a key of its own, as the same stream may also be attached to a
	   chain the node hosts */
	private static final AttributeKey<Boolean> CLOSE_AWAITED = AttributeKey.valueOf("followedChainsCloseAwaited");

	private final int max;
	private final Map<ChainId, HostedChain> replicas = new ConcurrentHashMap<>();

	Optional<HostedChain> find(final ChainId id) {
		return Optional.ofNullable(replicas.get(id));
	}

	Stream<HostedChain> all() {
		return replicas.values().stream();
	}

	/* A stream that is not open is none to follow through: its close has run or is running, and the replica would
	   keep it for good. A chain that is followed already takes another stream however many chains are followed. The
	   anchors start the feeds the replica lacks and never move one it has, and they are taken before the stream, so
	   that what the stream brings in finds its feed started. */
	synchronized boolean follow(final ChainId chainId, final Channel member, final Map<WriterId, Head> anchors,
		final Function<ChainId, HostedChain> replicaOf) {

		if (member == null || !member.isActive() || (replicas.size() >= max && !replicas.containsKey(chainId))) {
			return false;
		}

		final HostedChain replica = replicas.computeIfAbsent(chainId, replicaOf);

		anchors.forEach(replica::anchor);
		replica.addMemberStream(member);
		awaitClose(member);
		return true;
	}

	void unfollow(final ChainId chainId) {
		if (chainId != null) {
			replicas.remove(chainId);
		}
	}

	private void awaitClose(final Channel member) {
		if (member.attr(CLOSE_AWAITED).setIfAbsent(Boolean.TRUE) == null) {
			member.closeFuture().addListener(closed -> forget(member));
		}
	}

	/* A replica left without a stream is forgotten, as nothing reaches it any more */
	private synchronized void forget(final Channel member) {
		replicas.values().forEach(replica -> replica.dropMemberStream(member));
		replicas.values().removeIf(replica -> replica.getMemberStreams().isEmpty());
	}
}
