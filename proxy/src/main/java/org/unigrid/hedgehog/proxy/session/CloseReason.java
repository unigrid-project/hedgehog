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

package org.unigrid.hedgehog.proxy.session;

import java.util.Arrays;
import java.util.Optional;

public enum CloseReason {
	/* Either side finished */
	DONE(0),
	/* No traffic for sessionIdleSeconds */
	IDLE(1),
	/* The destination policy or the spork flags do not allow it */
	REFUSED(2),
	/* No exit answered, or the exit could not reach the destination */
	UNREACHABLE(3),
	/* An entry of the session was lost, replayed, tampered with or malformed */
	BROKEN(4);

	private final byte code;

	CloseReason(int code) {
		this.code = (byte) code;
	}

	public byte code() {
		return code;
	}

	public static Optional<CloseReason> of(byte code) {
		return Arrays.stream(values()).filter(reason -> reason.code == code).findFirst();
	}
}
