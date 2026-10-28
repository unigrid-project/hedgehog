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

package org.unigrid.hedgehog.proxy.session;

import java.util.Arrays;
import java.util.Objects;

/* A member that may be the exit of a session: its gridnode id and the public X25519 key the opening is sealed to */
public record ExitCandidate(String id, byte[] sealingKey) {
	public ExitCandidate {
		Objects.requireNonNull(id, "id");
		sealingKey = sealingKey.clone();
	}

	@Override
	public byte[] sealingKey() {
		return sealingKey.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ExitCandidate candidate && id.equals(candidate.id)
			&& Arrays.equals(sealingKey, candidate.sealingKey);
	}

	@Override
	public int hashCode() {
		return 31 * id.hashCode() + Arrays.hashCode(sealingKey);
	}

	@Override
	public String toString() {
		return "ExitCandidate[" + id + "]";
	}
}
