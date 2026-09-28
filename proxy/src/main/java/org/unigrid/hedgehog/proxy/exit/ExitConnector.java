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

package org.unigrid.hedgehog.proxy.exit;

import java.net.InetAddress;
import java.util.List;

/* Opens the exit's TCP connection to a destination */
@FunctionalInterface
public interface ExitConnector {
	/* Tries the addresses in order, as many as it is willing to, and stays with the first that accepts. It takes
	   addresses, never names, so it connects only to what the destination policy admitted. The listener hears
	   connected or failed once, from any thread, unless the attempt is cancelled first. */
	Attempt connect(List<InetAddress> addresses, int port, TargetListener listener);

	/* A connect under way, which its caller cancels once it no longer wants the connection */
	@FunctionalInterface
	interface Attempt {
		/* Nothing to cancel */
		Attempt NONE = () -> { };

		/* Tries no further address and closes the connection being dialled, of which the listener then hears
		   nothing. A connection the listener already has is its own to close: cancelling it does nothing, as does
		   cancelling again. Never waits and never calls the listener, so a caller may cancel under its own lock. */
		void cancel();
	}
}
