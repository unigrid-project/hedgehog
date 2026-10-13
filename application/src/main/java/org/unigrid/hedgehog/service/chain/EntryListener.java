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

import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;

@FunctionalInterface
public interface EntryListener {
	/* A chain took the entry in from another node, once and in the order the chain appended it, so an exit
	   that sees an OPEN here sees it exactly once. Entries this node posts itself are not offered. It runs on the
	   thread of a stream that is receiving entries for the chain, an event loop that holds no lock of the host, or
	   on the delivery thread of the host, but never on the thread of a caller that posts. No lock of the host is
	   held, so it may take its own locks and call back into the host. It should still only queue or hand over, as
	   the entries that come in on the chain meanwhile wait for it. A notification is missed when the chain holds
	   more waiting deliveries than it keeps, in which case the flood of those entries is lost too (members catch
	   up through reconcile, attached clients do not), or when an Error escapes a listener. */
	void appended(ChainId chainId, Entry entry);
}
