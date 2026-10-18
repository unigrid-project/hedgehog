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

package org.unigrid.hedgehog.model.storage;

import java.io.Serializable;
import lombok.Value;

/* Self-certifying, so any node can check and keep a tombstone without holding the group */
@Value
public class DeleteProof implements Serializable {
	private final byte[] publicKey;
	private final long timestamp;
	private final byte[] signature;

	public boolean isWellFormed() {
		return publicKey.length == GroupKey.PUBLIC_KEY_SIZE && signature.length == GroupKey.SIGNATURE_SIZE;
	}

	public boolean verifies(final GroupId groupId) {
		return isWellFormed() && GroupKey.groupIdOf(publicKey).equals(groupId)
			&& GroupKey.verify(publicKey, GroupKey.deleteMessage(groupId, timestamp), signature);
	}
}
