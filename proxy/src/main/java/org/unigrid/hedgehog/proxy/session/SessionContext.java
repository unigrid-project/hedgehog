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

package org.unigrid.hedgehog.proxy.session;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* What the sessions of one node on one chain share: the node's feed writer on that chain, the outbox that
   takes every entry they write to the chain, a clock in epoch milliseconds, randomness for keys and
   fingerprints, and the spork's settings */
public record SessionContext(FeedWriter writer, Consumer<Entry> outbox, LongSupplier clock, SecureRandom random,
	ProxySettings settings) {

	public SessionContext {
		Objects.requireNonNull(writer, "writer");
		Objects.requireNonNull(outbox, "outbox");
		Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(random, "random");
		Objects.requireNonNull(settings, "settings");
	}

	public long now() {
		return clock.getAsLong();
	}

	public int maxPayloadBytes() {
		return settings.chainLimits().maxPayloadBytes();
	}

	/* Holds the writer while posting, so the outbox sees the entries of the feed in sequence order. The
	   outbox therefore only queues or sends and never calls back into a session. It must not throw either: once
	   the writer has signed the entry, a failure leaves a gap in the whole feed of the writer, which every session
	   of this node on the chain shares, that no session can repair. */
	Entry post(Fingerprint fingerprint, EntryKind kind, byte[] payload) {
		synchronized (writer) {
			final Entry entry = writer.write(now(), fingerprint, kind, payload);

			outbox.accept(entry);
			return entry;
		}
	}
}
