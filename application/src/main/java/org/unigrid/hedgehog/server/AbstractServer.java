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

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import java.net.InetSocketAddress;

public abstract class AbstractServer {
	public abstract Channel getChannel();

	/* Netty hands out what failed a bind as it is, whatever its class and without declaring it. The server and the
	   options are those that an operator knows the address by, as in "REST" and "--resthost, --restport". */
	protected static Channel listen(final AbstractBootstrap<?, ?> bootstrap, final String host, final int port,
		final String server, final String options) throws InterruptedException, ListenException {

		try {
			return bootstrap.bind(new InetSocketAddress(host, port)).sync().channel();
		} catch (InterruptedException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new ListenException(host, port, server, options, ex);
		}
	}

	public String getHostName() {
		return ((InetSocketAddress) getChannel().localAddress()).getHostName();
	}

	public int getPort() {
		return ((InetSocketAddress) getChannel().localAddress()).getPort();
	}

	public ChannelId getChannelId() {
		return getChannel().id();
	}
}
