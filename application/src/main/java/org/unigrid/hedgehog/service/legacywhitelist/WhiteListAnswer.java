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

package org.unigrid.hedgehog.service.legacywhitelist;

import java.time.Instant;
import java.util.List;

/** What the whitelist says about one address. Sealed, so whoever answers must handle every case. */
public sealed interface WhiteListAnswer {
	/**
	 * On a list two board keys signed.
	 *
	 * @param signedBy the public keys of the signer and the co-signer
	 * @param publishTimeStamp when the board signed that version of the list
	 */
	record Approved(List<String> signedBy, Instant publishTimeStamp) implements WhiteListAnswer { }

	/** Not on the list the board signed. */
	record NotListed() implements WhiteListAnswer { }

	/** No list that two board keys signed is held yet, so nothing can be approved. */
	record NoSignedList() implements WhiteListAnswer { }

	/**
	 * Not a legacy address at all.
	 *
	 * @param reason why it does not decode
	 */
	record NotAnAddress(String reason) implements WhiteListAnswer { }
}
