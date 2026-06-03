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
import mockit.Capturing;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.server.TestServer;

public class NodeTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject
	private Topology topology;

	private enum Family {
		IP4, IP6
	}

	@Provide
	@SneakyThrows
	public Arbitrary<Node> provideNode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}

		return Arbitraries.of(node);
	}

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Property
	@SneakyThrows
	public void shouldBeAbleToCreateNodeFromAddress(@ForAll("provideNode") Node node) {
		assertThat(node, notNullValue());
	}

	@Property(tries = 100)
	public void shouldFilterIfMe(@ForAll("provideNode") Node node, @ForAll boolean me) {
		new Expectations(node) {{
			node.isMe(); result = me;
		}};

		final int originalSize = topology.cloneNodes().size();

		if (topology.containsNode(node) || me) {
			topology.addNode(node);
			assertThat(topology.cloneNodes().size(), is(originalSize));
		} else {
			topology.addNode(node);
			assertThat(topology.cloneNodes().size(), is(originalSize + 1));
		}
	}
}
