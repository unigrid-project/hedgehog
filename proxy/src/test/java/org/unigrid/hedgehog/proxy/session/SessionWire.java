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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* A client and one exit gridnode, each with its own replica of one chain. Every posted entry is offered to
   both replicas and what a replica appends goes to its side, as chain hosts would do over the network. The
   exit answers the OPENs that name exitId, keeps one session per fingerprint and collects what it receives. */
final class SessionWire {
	final AtomicLong clock = new AtomicLong(SessionFixtures.NOW);
	final Chain clientChain = new Chain(SessionFixtures.CHAIN, ProxySettings.DEFAULTS.chainLimits(), clock::get);
	final Chain exitChain = new Chain(SessionFixtures.CHAIN, ProxySettings.DEFAULTS.chainLimits(), clock::get);
	final Deque<Entry> posted = new ArrayDeque<>();
	final SessionContext clientContext = context(1);
	final SessionContext exitContext = context(2);
	final Map<Fingerprint, Session> exits = new HashMap<>();
	final List<SealedMessage> atClient = new ArrayList<>();
	final List<SealedMessage> atExit = new ArrayList<>();
	final String exitId;
	ClientSession client;

	SessionWire(String exitId) {
		this.exitId = exitId;
	}

	/* Writer number on the chain of this wire, whose entries go out like everyone else's */
	SessionContext context(int number) {
		return new SessionContext(new FeedWriter(SessionFixtures.key(number), SessionFixtures.CHAIN), posted::add,
			clock::get, new SecureRandom(), ProxySettings.DEFAULTS);
	}

	ClientSession client(ExitSelector selector, Connect connect) {
		client = new ClientSession(clientContext, selector, connect);
		return client;
	}

	void pump() {
		while (!posted.isEmpty()) {
			final Entry entry = posted.poll();

			clientChain.offer(entry).appended().forEach(appended -> client.receive(appended).ifPresent(atClient::add));
			exitChain.offer(entry).appended().forEach(this::atExitSide);
		}
	}

	Session exitSession() {
		return exits.get(client.fingerprint().orElseThrow());
	}

	private void atExitSide(Entry entry) {
		if (entry.header().kind() == EntryKind.OPEN) {
			Session.accept(exitContext, entry, exitId).ifPresent(session -> exits.put(session.fingerprint(), session));
		}

		Optional.ofNullable(exits.get(entry.header().fingerprint())).flatMap(session -> session.receive(entry))
			.ifPresent(atExit::add);
	}
}
