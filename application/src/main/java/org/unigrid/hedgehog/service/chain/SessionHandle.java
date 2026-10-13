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

import org.unigrid.hedgehog.proxy.session.CloseReason;
import org.unigrid.hedgehog.proxy.session.SealedMessage;

/* The opener's handle on one client session */
public interface SessionHandle {
	/* Posts one message to the exit; IllegalStateException while the session is not open and once it has ended,
	   IllegalArgumentException for a message that does not fit one entry. A Close ends the session like close. */
	void send(SealedMessage message);

	/* False while the chain takes no more of what the session sends: the opener stops producing until the
	   listener hears that it does */
	boolean isWritable();

	/* The most bytes one Data message can carry; zero until the chain of the session is joined */
	int maxDataBytes();

	/* Ends the session from the opener's side and tells the exit; the listener hears nothing more, but for a message
	   it was being told when this was called */
	void close(CloseReason reason);
}
