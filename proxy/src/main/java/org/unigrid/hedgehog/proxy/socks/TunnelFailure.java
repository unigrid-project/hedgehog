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

import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.util.Arrays;
import org.unigrid.hedgehog.proxy.session.CloseReason;

/* Why a stream did not open, as the SOCKS5 reply the front end sends its client */
public enum TunnelFailure {
	GENERAL_FAILURE(Socks5CommandStatus.FAILURE),
	NOT_ALLOWED(Socks5CommandStatus.FORBIDDEN),
	NETWORK_UNREACHABLE(Socks5CommandStatus.NETWORK_UNREACHABLE),
	HOST_UNREACHABLE(Socks5CommandStatus.HOST_UNREACHABLE),
	CONNECTION_REFUSED(Socks5CommandStatus.CONNECTION_REFUSED),
	TTL_EXPIRED(Socks5CommandStatus.TTL_EXPIRED),
	COMMAND_UNSUPPORTED(Socks5CommandStatus.COMMAND_UNSUPPORTED),
	ADDRESS_UNSUPPORTED(Socks5CommandStatus.ADDRESS_UNSUPPORTED);

	private final Socks5CommandStatus status;

	TunnelFailure(Socks5CommandStatus status) {
		this.status = status;
	}

	public Socks5CommandStatus status() {
		return status;
	}

	/* The failure an exit's unsuccessful Connected reply names; a code SOCKS5 does not define is a general one */
	public static TunnelFailure of(Socks5CommandStatus status) {
		if (status.isSuccess()) {
			throw new IllegalArgumentException("A successful reply is not a failure");
		}

		return Arrays.stream(values()).filter(failure -> failure.status.equals(status)).findFirst()
			.orElse(GENERAL_FAILURE);
	}

	/* The failure of a session that closed before its exit replied */
	public static TunnelFailure of(CloseReason reason) {
		switch (reason) {
			case REFUSED:
				return NOT_ALLOWED;
			case UNREACHABLE:
				return NETWORK_UNREACHABLE;
			case IDLE:
				return TTL_EXPIRED;
			default:
				return GENERAL_FAILURE;
		}
	}
}
