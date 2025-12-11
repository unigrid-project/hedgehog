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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* One chain this node hosts, with what the host knows about it beyond the entries */
@RequiredArgsConstructor
final class HostedChain {
	enum Attachment { NEW, KNOWN, REFUSED }

	@Getter private final Chain chain;
	private final Map<WriterId, Channel> clients = new ConcurrentHashMap<>();

	/* The streams this node holds to the other members of the group, looked up when the hosting is refreshed.
	   Replaced as a whole by an immutable list and never changed in place, volatile so that the netty threads
	   which read it see the list the refresh thread has just written. */
	@Getter @Setter private volatile List<Channel> memberStreams = List.of();

	int clientCount() {
		return clients.size();
	}

	boolean isAttached(final WriterId writer) {
		return clients.containsKey(writer);
	}

	long clientsOn(final Channel stream) {
		return clients.values().stream().filter(stream::equals).count();
	}

	/* A writer keeps the live stream it attached through, so another stream cannot take its place by naming it,
	   and a stream without room left may only renew what it has. Only joins call this, one at a time, so a
	   chain never gets past its limit. */
	Attachment attach(final WriterId writer, final Channel stream, final int limit, final boolean streamHasRoom) {
		final Channel registered = clients.get(writer);

		if (registered == stream) {
			return Attachment.KNOWN;
		}

		if (!streamHasRoom || (registered == null ? clients.size() >= limit : registered.isActive())) {
			return Attachment.REFUSED;
		}

		clients.put(writer, stream);
		return Attachment.NEW;
	}

	void detach(final WriterId writer, final Channel stream) {
		clients.remove(writer, stream);
	}

	void detachAll(final Channel stream) {
		clients.values().removeIf(stream::equals);
	}
}
