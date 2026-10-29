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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.session.Data;
import org.unigrid.hedgehog.proxy.session.SealedMessage;
import org.unigrid.hedgehog.proxy.session.Session;
import org.unigrid.hedgehog.proxy.session.SessionContext;

/* The exit end of the sessions sealed to one gridnode, without a destination behind it: it answers each opening its
   sealing key opens, collects what the client sends, and sends what a test tells it to. Like the exit of a gridnode,
   it writes each session as a writer of its own, whose feed the Close of the session ends. */
final class ExitStub {
	final List<Session> sessions = new ArrayList<>();
	final List<SealedMessage> received = new CopyOnWriteArrayList<>();
	volatile boolean muted;
	private final ChainFleet fleet;
	private final String id;
	private final int number;
	private List<Entry> capture;

	ExitStub(final ChainFleet fleet, final int number) {
		this.fleet = fleet;
		this.id = "gn-" + number;
		this.number = number;
		fleet.host(id).addEntryListener(this::appended);
	}

	synchronized Session session() {
		return sessions.get(0);
	}

	/* The session sends two messages that nobody gets, and the second is returned: a chain that is handed only the
	   second gives up on the first once the second has waited long enough, and the gap names the tag of the second */
	synchronized Entry skipOne(final Session session) {
		final List<Entry> captured = new ArrayList<>();

		capture = captured;

		try {
			session.send(new Data(new byte[] { 98 }));
			session.send(new Data(new byte[] { 99 }));
		} finally {
			capture = null;
		}

		return captured.get(1);
	}

	private synchronized void appended(final ChainId chainId, final Entry entry) {
		if (muted) {
			return;
		}

		final Optional<Session> known = sessions.stream().filter(session -> session.recognises(entry)).findFirst();

		if (known.isPresent()) {
			known.get().receive(entry).ifPresent(received::add);
		} else {
			Session.tryAccept(context(chainId), entry, ChainFleet.sealingKey(id).privateKey().orElseThrow())
				.ifPresent(sessions::add);
		}
	}

	/* The writer of the next session of this stub, drawn from its number and how many sessions it has */
	private SessionContext context(final ChainId chainId) {
		return new SessionContext(new FeedWriter(ChainFleet.key(1000 * (number + 1) + sessions.size()), chainId),
			this::post, fleet.clock::get, new SecureRandom(), fleet.settings.get().orElseThrow());
	}

	/* Called with the stub held, as its sessions only send from a test or from appended */
	private void post(final Entry entry) {
		if (capture == null) {
			fleet.host(id).post(entry);
		} else {
			capture.add(entry);
		}
	}
}
