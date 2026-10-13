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

package org.unigrid.hedgehog.proxy.socks;

import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* What a tunnel tells the front end about one stream, in this order: connected or failed once, then received
   and writabilityChanged until closed. Calls may come from any thread. */
public interface TunnelListener {
	/* The exit reached the destination from the bound address */
	void connected(SocksAddress bound);

	/* The stream never opened; nothing follows */
	void failed(TunnelFailure failure);

	/* Bytes from the destination, in order */
	void received(byte[] bytes);

	/* Whether the stream takes more writes */
	void writabilityChanged(boolean writable);

	/* The destination or the exit ended the stream; nothing follows */
	void closed();
}
