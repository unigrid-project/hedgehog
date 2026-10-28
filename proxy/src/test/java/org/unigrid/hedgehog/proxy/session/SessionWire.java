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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

/* A client and one exit gridnode, each with its own replica of one chain. Every posted entry is offered to
   both replicas and what a replica appends goes to its side, as chain hosts would do over the network. The
   exit holds the sealing key of exitId, answers every opening sealed to it, hands each entry to the session
   that recognises its tag and collects what it receives. */
final class SessionWire {
	final AtomicLong clock = new AtomicLong(SessionFixtures.NOW);
	final ProxySettings settings;
	final Chain clientChain;
	final Chain exitChain;
	final Deque<Entry> posted = new ArrayDeque<>();
	final SessionContext clientContext;
	final SessionContext exitContext;
	final List<Session> exits = new ArrayList<>();
	final List<SealedMessage> atClient = new ArrayList<>();
	final List<SealedMessage> atExit = new ArrayList<>();
	/* Entries that never reach the exit's replica, as if lost on the way */
	final Set<Entry> lostAtExit = new HashSet<>();
	final String exitId;
	ClientSession client;

	SessionWire(String exitId) {
		this(exitId, ProxySettings.DEFAULTS);
	}

	SessionWire(String exitId, ProxySettings settings) {
		this.exitId = exitId;
		this.settings = settings;
		clientChain = new Chain(SessionFixtures.CHAIN, settings.chainLimits(), clock::get);
		exitChain = new Chain(SessionFixtures.CHAIN, settings.chainLimits(), clock::get);
		clientContext = context(1);
		exitContext = context(2);
	}

	/* Writer number on the chain of this wire, whose entries go out like everyone else's */
	SessionContext context(int number) {
		return new SessionContext(new FeedWriter(SessionFixtures.key(number), SessionFixtures.CHAIN), posted::add,
			clock::get, new SecureRandom(), settings);
	}

	ClientSession client(ExitSelector selector, Connect connect) {
		client = new ClientSession(clientContext, selector, connect);
		return client;
	}

	void pump() {
		while (!posted.isEmpty()) {
			final Entry entry = posted.poll();

			clientChain.offer(entry).appended().forEach(appended -> client.receive(appended).ifPresent(atClient::add));

			if (!lostAtExit.remove(entry)) {
				exitChain.offer(entry).appended().forEach(this::atExitSide);
			}
		}
	}

	/* The session of the latest opening the exit answered */
	Session exitSession() {
		return exits.get(exits.size() - 1);
	}

	private void atExitSide(Entry entry) {
		if (entry.header().writer().equals(exitContext.writer().writer())) {
			return;
		}

		final Optional<Session> known = exits.stream().filter(session -> session.recognises(entry)).findFirst();

		if (known.isPresent()) {
			known.get().receive(entry).ifPresent(atExit::add);
		} else {
			Session.tryAccept(exitContext, entry, SessionFixtures.sealingPrivate(exitId)).ifPresent(exits::add);
		}
	}
}
