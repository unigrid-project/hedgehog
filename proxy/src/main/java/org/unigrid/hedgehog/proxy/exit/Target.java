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

package org.unigrid.hedgehog.proxy.exit;

import java.net.InetSocketAddress;

/* The exit's open connection to a destination. Reads start off: the listener turns them on when it is ready
   for what the destination sends. */
public interface Target {
	/* The exit's own end of the connection, which the reply to the client never names */
	InetSocketAddress localAddress();

	/* Queues bytes towards the destination without a bound, so a caller that must not buffer for ever checks
	   backlogBytes. The array is queued, not copied: it must not change afterwards. Bytes written after a close are
	   dropped. */
	void write(byte[] bytes);

	/* Bytes queued towards the destination and not yet taken by its socket, plus a few dozen of bookkeeping for
	   each queued write */
	long backlogBytes();

	/* Off stops further reads, but a read already under way is still delivered */
	void setReadable(boolean readable);

	/* Stops reading at once, gives the destination a few seconds to take what is queued and then closes. Closing
	   again, or after the connection has ended, does nothing. */
	void close();
}
