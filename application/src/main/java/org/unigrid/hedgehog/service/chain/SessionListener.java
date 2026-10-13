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

import org.unigrid.hedgehog.proxy.session.SealedMessage;

/* What a client session tells whoever opened it. Calls may come from any thread but never with a lock of the
   session held, so a listener may call back into its handle. */
public interface SessionListener {
	/* A message of the exit, one at a time and in order. Every way a session ends but its opener's close leaves a
	   Close here, the last message: the exit's own, an unanswered open, the idle limit, a chain that lost entries
	   or could not be joined. */
	void received(SealedMessage message);

	/* Whether the chain takes more of what the session sends, told about once a second whether it changed or not,
	   as the opener may have read otherwise from its handle meanwhile */
	void writabilityChanged(boolean writable);
}
