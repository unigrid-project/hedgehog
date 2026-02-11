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

package org.unigrid.hedgehog.proxy.exit;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.WriterKey;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.ClientSession;
import org.unigrid.hedgehog.proxy.session.Connect;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* A client session and the exit session that accepted it, each side's entries queued for the other as chain
   replicas would hand them over. The queues take entries from any thread; the client has already sent its
   Connect when the constructor returns. */
final class SessionPair {
	static final long NOW = 1_750_000_000_000L;
	static final String EXIT_ID = "gn-exit";
	static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);

	final AtomicLong clock = new AtomicLong(NOW);
	/* While set, the exit's outbox throws instead of queueing */
	final AtomicBoolean refusing = new AtomicBoolean();
	final Queue<Entry> fromClient = new ConcurrentLinkedQueue<>();
	final Queue<Entry> fromExit = new ConcurrentLinkedQueue<>();
	final ClientSession client;
	final Session exit;

	SessionPair(Connect connect) {
		client = new ClientSession(context(1, fromClient::add), (fingerprint, excluded) -> Optional.of(EXIT_ID),
			connect);
		client.open();
		exit = Session.accept(context(2, this::post), fromClient.poll(), EXIT_ID).orElseThrow();
		client.receive(fromExit.poll());
	}

	/* What the exit session opened of the client's entries since the last call */
	List<SealedMessage> toExit() {
		return deliver(fromClient, exit::receive);
	}

	/* What the client opened of the exit's entries since the last call */
	List<SealedMessage> toClient() {
		return deliver(fromExit, client::receive);
	}

	private SessionContext context(int writer, Consumer<Entry> outbox) {
		final byte[] seed = new byte[WriterKey.SEED_SIZE];

		Arrays.fill(seed, (byte) writer);
		return new SessionContext(new FeedWriter(new WriterKey(seed), CHAIN), outbox, clock::get,
			new SecureRandom(), ProxySettings.DEFAULTS);
	}

	private void post(Entry entry) {
		if (refusing.get()) {
			throw new OutboxRefusedException();
		}

		fromExit.add(entry);
	}

	private static List<SealedMessage> deliver(Queue<Entry> entries, Function<Entry, Optional<SealedMessage>> to) {
		final List<SealedMessage> messages = new ArrayList<>();

		for (Entry entry = entries.poll(); entry != null; entry = entries.poll()) {
			to.apply(entry).ifPresent(messages::add);
		}

		return messages;
	}

	static final class OutboxRefusedException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
