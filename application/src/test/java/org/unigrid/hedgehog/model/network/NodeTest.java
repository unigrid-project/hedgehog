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

package org.unigrid.hedgehog.model.network;

import jakarta.inject.Inject;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

public class NodeTest extends BaseMockedWeldTest {
	@Inject
	private Topology topology;

	@Property
	@SneakyThrows
	public void shouldBeAbleToCreateNodeFromV4Address(@ForAll @IntRange(min = 1024, max = 65535) int port) {
		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress(ArbitraryGenerator.ip4());
		} else {
			node = Node.fromAddress("%s:%d".formatted(ArbitraryGenerator.ip4(), port));
		}

		assertThat(node, notNullValue());
	}

	@Property
	@SneakyThrows
	public void shouldBeAbleToCreateNodeFromV6Address(@ForAll @IntRange(min = 1024, max = 65535) int port) {
		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress("[%s]".formatted(ArbitraryGenerator.ip6()));
		} else {
			node = Node.fromAddress("[%s]:%d".formatted(ArbitraryGenerator.ip6(), port));
		}

		assertThat(node, notNullValue());
	}
}
