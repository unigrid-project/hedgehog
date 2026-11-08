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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.option.PreviewOptions;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/* The spork types that this node takes. Those of the parts this release leaves off are taken only by a node run with
   --preview: one without it neither stores, records, relays, sends nor shows a spork of them, and acts on none that
   its database still holds from a run with it. A spork of such a type is dropped once it is read, so that its frame
   is still read whole. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkRelease {
	private static final Set<Type> PREVIEW = Set.of(Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	public static boolean isTaken(final Type type) {
		return type != Type.UNDEFINED && (PreviewOptions.isPreview() || !PREVIEW.contains(type));
	}

	/* In the order the types are listed, so that what shows them stays as it is */
	public static List<Type> types() {
		return Arrays.stream(Type.values()).filter(SporkRelease::isTaken).toList();
	}

	/* What the database holds of a type is read only for a type that the node takes */
	public static Optional<GridSpork> stored(final SporkDatabase database, final Type type) {
		return isTaken(type) ? Optional.ofNullable(database.get(type)) : Optional.empty();
	}
}
