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

package org.unigrid.hedgehog.proxy.socks;

import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* What the SOCKS front end needs from the network: a stream to a destination, and an association for datagrams.
   The client side of the proxy implements it by driving a session over a shard group chain, so the exit resolves
   names, connects and relays. */
@FunctionalInterface
public interface Tunnel {
	/* Starts opening a stream and returns its handle at once. The listener hears connected or failed exactly
	   once, within a bounded time, then received and writabilityChanged until closed. A stream that goes silent
	   ends with closed: the front end cuts nothing after the command, so the deadline is the tunnel's. Its calls
	   may come from any thread, even from within open. */
	TunnelStream open(SocksAddress destination, TunnelListener listener);

	/* Starts opening a UDP association and returns its handle at once. The listener hears opened or failed exactly
	   once, within a bounded time, then received until closed, from any thread, even from within associate. A
	   tunnel that does not relay datagrams fails every association before associate returns, as a command it does
	   not support. */
	default AssociationStream associate(AssociationListener listener) {
		return refusing(TunnelFailure.COMMAND_UNSUPPORTED).associate(listener);
	}

	/* A tunnel that fails every stream and association for the same reason */
	static Tunnel refusing(TunnelFailure failure) {
		return new RefusingTunnel(failure);
	}
}
