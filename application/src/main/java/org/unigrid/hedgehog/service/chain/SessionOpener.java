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

package org.unigrid.hedgehog.service.chain;

import java.util.Optional;
import org.unigrid.hedgehog.proxy.session.Connect;

@FunctionalInterface
public interface SessionOpener {
	/* Starts a session towards an exit that carries the Connect, and returns its handle at once: the chain is
	   joined, the exit chosen and the handshake made in the background, and the listener hears the outcome. When
	   the chain is joined already, the handshake is made, and a failure told, on the calling thread. The country is
	   the upper-case ISO 3166-1 alpha-2 code of the country the exit is to be in, empty for any. */
	SessionHandle open(Connect connect, Optional<String> country, SessionListener listener);
}
