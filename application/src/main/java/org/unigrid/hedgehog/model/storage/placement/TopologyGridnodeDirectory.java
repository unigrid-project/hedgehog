/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.storage.placement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

@RequiredArgsConstructor
public class TopologyGridnodeDirectory implements GridnodeDirectory {
	private final Supplier<Collection<Gridnode>> gridnodes;
	private final Supplier<String> selfId;

	@Override
	public List<Gridnode> active() {
		return gridnodes.get().stream().filter(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE)
			.collect(Collectors.toList());
	}

	@Override
	public Optional<String> self() {
		return Optional.ofNullable(StringUtils.trimToNull(selfId.get()));
	}
}
