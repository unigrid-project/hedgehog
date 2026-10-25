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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.codec.binary.Hex;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.NetworkKey;

/* The board keys as a node goes by them, for people to read: the epoch and the keys built into the release, which the
   chain is rooted in, the chain of changes with the digest each follows on, and the keys active now and those that the
   chain switched off */
public record BoardKeysInfo(long epoch, List<String> builtInKeys, List<Change> chain, Set<String> active,
	Set<String> inactive) {

	public record Change(int sequence, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant madeAt, String previous,
		List<String> active, List<Approval> approvals) {
	}

	public record Approval(String key, String signature) {
	}

	public static BoardKeysInfo of(BoardKeys stored) {
		final List<KeyChange> changes = Objects.isNull(stored) ? List.of()
			: stored.<BoardKeys.SporkData>getData().getChanges();
		final ActiveKeys keys = NetworkKey.getActiveKeys();

		return new BoardKeysInfo(Network.BOARD_KEYS_EPOCH, List.of(NetworkKey.getBuiltInKeys()),
			changes.stream().map(BoardKeysInfo::changeOf).toList(), keys.active(), keys.inactive());
	}

	private static Change changeOf(KeyChange change) {
		return new Change(change.getSequence(), change.getMadeAt(), Hex.encodeHexString(change.getPrevious()),
			change.getActive(), change.getApprovals().stream().map(approval -> new Approval(approval.getKey(),
			Hex.encodeHexString(approval.getSignature()))).toList());
	}
}
