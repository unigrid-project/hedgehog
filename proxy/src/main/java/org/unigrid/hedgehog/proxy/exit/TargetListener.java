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

import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;

/* What an exit connector tells its caller, in this order: connected or failed once, then received until
   closed. The calls come from the connection's event loop, which serves many connections, so they must not block;
   a failure for an empty address list or a stopped group comes from the thread that asked to connect. */
public interface TargetListener {
	void connected(Target target);

	/* No admitted address accepted; the status is the SOCKS5 reply for the last attempt */
	void failed(Socks5CommandStatus status);

	/* Bytes from the destination, in order */
	void received(byte[] bytes);

	/* The connection ended, by the destination, by an error or by a close of the target; nothing follows */
	void closed();
}
