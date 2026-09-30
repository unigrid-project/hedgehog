/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

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

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Chars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.WithNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

public class TopologyGridnodeDirectoryTest {
	private static final List<Gridnode> NO_GRIDNODES = List.of();

	@Property
	public void listsOnlyActiveGridnodesInOrder(@ForAll List<Gridnode.Status> statuses) {
		final List<Gridnode> gridnodes = IntStream.range(0, statuses.size()).mapToObj(i -> Gridnode.builder()
			.id("gridnode-" + i).status(statuses.get(i)).build()).collect(Collectors.toList());
		final List<Gridnode> active = gridnodes.stream()
			.filter(gridnode -> gridnode.getStatus() == Gridnode.Status.ACTIVE).collect(Collectors.toList());

		assertThat(new TopologyGridnodeDirectory(() -> gridnodes, () -> "").active(), equalTo(active));
	}

	@Property
	public void hasNoSelfForABlankId(@ForAll @WithNull @Chars({' ', '\t', '\n', '\r'}) String id) {
		assertThat(new TopologyGridnodeDirectory(() -> NO_GRIDNODES, () -> id).self(), equalTo(Optional.empty()));
	}

	@Property
	public void trimsTheSelfId(@ForAll @AlphaChars @NumericChars @Chars({'-', '.'}) @StringLength(min = 1) String id,
		@ForAll @Chars({' ', '\t', '\n', '\r'}) String leading,
		@ForAll @Chars({' ', '\t', '\n', '\r'}) String trailing) {

		assertThat(new TopologyGridnodeDirectory(() -> NO_GRIDNODES, () -> leading + id + trailing).self(),
			equalTo(Optional.of(id)));
	}
}
