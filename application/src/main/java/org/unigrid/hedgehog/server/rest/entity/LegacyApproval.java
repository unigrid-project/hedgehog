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

package org.unigrid.hedgehog.server.rest.entity;

import java.time.Instant;
import java.util.List;

/**
 * That a legacy address is on the whitelist the board signed, and by whom.
 *
 * @param address the legacy address that was asked about
 * @param approved whether it is approved, which it is whenever this answer is given
 * @param signedBy the public keys of the board keys that signed and co-signed the version of the list
 * @param publishTimeStamp when the board signed that version of the whole list, not when the address joined it
 */
public record LegacyApproval(String address, boolean approved, List<String> signedBy, Instant publishTimeStamp) {
}
