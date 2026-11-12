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

package org.unigrid.hedgehog.server;

import io.netty.util.NetUtil;
import java.net.BindException;
import java.util.Objects;

/* A server that cannot listen on the address it was given. What fails a bind says why and nothing else, as in "Address
   already in use", and a node listens on more than one port, so the message names the address, the server and the
   options that set the address. Some failures have no message, and are told by their class. */
public class ListenException extends BindException {
	public ListenException(final String host, final int port, final String server, final String options,
		final Exception cause) {

		super("Cannot listen on %s for %s (%s): %s".formatted(NetUtil.toSocketAddressString(host, port), server,
			options, Objects.toString(cause.getMessage(), cause.getClass().getSimpleName())));

		initCause(cause);
	}
}
