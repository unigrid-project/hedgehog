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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

public class TopologyGridnodeDirectoryTest {
	@Example
	public void listsOnlyActiveGridnodes() {
		final Gridnode active = Gridnode.builder().id("a").status(Gridnode.Status.ACTIVE).build();
		final Gridnode inactive = Gridnode.builder().id("b").status(Gridnode.Status.INACTIVE).build();
		final GridnodeDirectory directory = new TopologyGridnodeDirectory(() -> List.of(active, inactive), () -> "a");

		assertThat(directory.active(), contains(active));
		assertThat(directory.self().get(), equalTo("a"));
	}

	@Example
	public void hasNoSelfWithoutAGridnodeKey() {
		assertThat(new TopologyGridnodeDirectory(List::of, () -> " ").self().isPresent(), is(false));
	}
}
