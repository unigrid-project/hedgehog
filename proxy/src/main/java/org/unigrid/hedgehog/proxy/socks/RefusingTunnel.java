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

import java.util.Objects;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* Fails every stream before open returns, so a caller must not assume it holds the handle when failed is told; the
   stream it hands back takes nothing and ends nothing */
final class RefusingTunnel implements Tunnel, TunnelStream {
	private final TunnelFailure failure;

	RefusingTunnel(TunnelFailure failure) {
		this.failure = Objects.requireNonNull(failure, "failure");
	}

	@Override
	public TunnelStream open(SocksAddress destination, TunnelListener listener) {
		listener.failed(failure);
		return this;
	}

	@Override
	public void write(byte[] bytes) {
		/* Nothing was opened to write to */
	}

	@Override
	public boolean isWritable() {
		return false;
	}

	@Override
	public void setReadable(boolean readable) {
		/* Nothing will ever arrive */
	}

	@Override
	public void close() {
		/* Nothing was opened */
	}
}
