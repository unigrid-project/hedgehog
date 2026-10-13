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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.unigrid.hedgehog.model.network.packet.Correlated;

@ApplicationScoped
public class PendingRequests {
	public static final Duration TIMEOUT = Duration.ofSeconds(10);

	private record Pending(Channel channel, Class<? extends Correlated> type, CompletableFuture<Correlated> future) {
		/* A peer answering with the wrong packet type must neither consume nor fail the request */
		private boolean awaits(final Channel origin, final Correlated response) {
			return channel.equals(origin) && type.isInstance(response);
		}
	}

	private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong(new SecureRandom().nextLong());
	private final Duration timeout;

	public PendingRequests() {
		this(TIMEOUT);
	}

	PendingRequests(final Duration timeout) {
		this.timeout = timeout;
	}

	int size() {
		return pending.size();
	}

	public long nextRequestId() {
		return sequence.incrementAndGet();
	}

	public <T extends Correlated> CompletableFuture<T> register(final long requestId, final Channel channel,
		final Class<T> type) {

		final Pending request = new Pending(channel, type, new CompletableFuture<>());

		pending.put(requestId, request);

		/* Chained after the removal, so a caller that sees the reply or the timeout also sees the entry gone */
		return request.future().orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
			.whenComplete((response, error) -> pending.remove(requestId, request))
			.thenApply(type::cast);
	}

	public void complete(final Channel channel, final Correlated response) {
		final long requestId = response.getRequestId();
		final Pending waiting = pending.get(requestId);

		if (waiting != null && waiting.awaits(channel, response) && pending.remove(requestId, waiting)) {
			waiting.future().complete(response);
		}
	}
}
