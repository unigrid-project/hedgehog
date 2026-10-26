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

package org.unigrid.hedgehog.service.chain;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* The exit end of the sessions that name one gridnode, without a destination behind it: it answers each OPEN
   with an ACCEPT, collects what the client sends, and sends what a test tells it to */
final class ExitStub {
	final Map<Fingerprint, Session> sessions = new LinkedHashMap<>();
	final List<SealedMessage> received = new CopyOnWriteArrayList<>();
	volatile boolean muted;
	private final ChainFleet fleet;
	private final String id;
	private final int number;
	private final Map<ChainId, SessionContext> contexts = new HashMap<>();

	ExitStub(final ChainFleet fleet, final int number) {
		this.fleet = fleet;
		this.id = "gn-" + number;
		this.number = number;
		fleet.host(id).addEntryListener(this::appended);
	}

	Session session() {
		return sessions.values().iterator().next();
	}

	/* Writes an entry of the stub's feed that nobody gets and returns the one after it: a chain that is handed only
	   the second gives up on the first once the second has waited long enough, and the gap names the fingerprint of
	   the second */
	synchronized Entry skipOne(final ChainId chainId, final Fingerprint fingerprint) {
		final FeedWriter feed = context(chainId).writer();

		feed.write(fleet.clock.get(), fingerprint, EntryKind.SEALED, new byte[1]);
		return feed.write(fleet.clock.get(), fingerprint, EntryKind.SEALED, new byte[1]);
	}

	private synchronized void appended(final ChainId chainId, final Entry entry) {
		final Session known = sessions.get(entry.header().fingerprint());

		if (muted) {
			return;
		}

		if (entry.header().kind() == EntryKind.OPEN) {
			Session.accept(context(chainId), entry, id).ifPresent(session -> sessions.put(session.fingerprint(),
				session));
		} else if (known != null) {
			known.receive(entry).ifPresent(received::add);
		}
	}

	private SessionContext context(final ChainId chainId) {
		return contexts.computeIfAbsent(chainId, chain -> new SessionContext(new FeedWriter(
			ChainFleet.key(100 + number), chain), fleet.host(id)::post, fleet.clock::get, new SecureRandom(),
			fleet.settings.get().orElseThrow()));
	}
}
