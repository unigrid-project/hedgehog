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

package org.unigrid.hedgehog.proxy.session;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.SizeClasses;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* What the sessions of one node on one chain share: the node's feed writer on that chain, the outbox that
   takes every entry they write to the chain, a clock in epoch milliseconds, randomness for keys, tags and
   padding, and the spork's settings */
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

	/* The payload of an entry of the smallest size class, which openings and answers fill */
	int smallestPayloadBytes() {
		return paddedPayloadBytes(0);
	}

	/* The payload of the smallest size class whose entries hold a payload of the given size */
	int paddedPayloadBytes(int payloadBytes) {
		final OptionalInt entryBytes = SizeClasses.fit(Entry.OVERHEAD + payloadBytes,
			settings.chainLimits().maxEntryBytes());

		if (entryBytes.isEmpty()) {
			throw new IllegalArgumentException("A payload of " + payloadBytes + " bytes does not fit an entry");
		}

		return entryBytes.getAsInt() - Entry.OVERHEAD;
	}

	/* The message followed by random bytes up to the payload of the smallest class that holds it */
	byte[] padded(byte[] message) {
		final byte[] padding = new byte[paddedPayloadBytes(message.length) - message.length];

		random.nextBytes(padding);

		final byte[] payload = Arrays.copyOf(message, message.length + padding.length);

		System.arraycopy(padding, 0, payload, message.length, padding.length);
		return payload;
	}

	/* Holds the writer while posting, so the outbox sees the entries of the feed in sequence order. The
	   outbox therefore only queues or sends and never calls back into a session. It must not throw either: once
	   the writer has signed the entry, a failure leaves a gap in the whole feed of the writer, which every session
	   of this node on the chain shares, that no session can repair. */
	Entry post(Tag tag, byte[] payload) {
		return post(tag, payload, false);
	}

	/* The last entry of the writer's feed, after which the writer posts nothing */
	Entry post(Tag tag, byte[] payload, boolean last) {
		synchronized (writer) {
			final Entry entry = last ? writer.writeLast(now(), tag, payload) : writer.write(now(), tag, payload);

			outbox.accept(entry);
			return entry;
		}
	}
}
