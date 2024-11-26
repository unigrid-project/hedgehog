/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

	private record Pending(Channel channel, CompletableFuture<Correlated> future) {
		/* A request waiting for its reply on one specific channel */
	}

	private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
	private final AtomicLong sequence = new AtomicLong(new SecureRandom().nextLong());

	public long nextRequestId() {
		return sequence.incrementAndGet();
	}

	public <T extends Correlated> CompletableFuture<T> register(long requestId, Channel channel, Class<T> type) {
		final CompletableFuture<Correlated> future = new CompletableFuture<>();

		pending.put(requestId, new Pending(channel, future));
		future.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
			.whenComplete((response, error) -> pending.remove(requestId));

		return future.thenApply(type::cast);
	}

	public void complete(Channel channel, Correlated response) {
		final Pending waiting = pending.get(response.getRequestId());

		if (waiting != null && waiting.channel().equals(channel)) {
			pending.remove(response.getRequestId());
			waiting.future().complete(response);
		}
	}
}
