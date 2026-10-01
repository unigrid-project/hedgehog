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

package org.unigrid.hedgehog.command.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridnodeListTest {
	private static final Request LISTING = new Request(HttpMethod.GET, "/gridnode", Optional.empty(),
		Optional.empty());

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Provide
	public Arbitrary<Set<Gridnode>> gridnodes() {
		final Arbitrary<String> text = Arbitraries.strings().alpha().numeric().ofMaxLength(16);

		return Combinators.combine(text, text, Arbitraries.of(Gridnode.Status.class), Arbitraries.longs())
			.as((id, host, status, timestamp) -> Gridnode.builder().id(id).hostName(host).status(status)
				.timestamp(timestamp).build()).set().ofMaxSize(5);
	}

	@SneakyThrows
	@Property(tries = 30)
	public void printsTheGridnodesAsJson(@ForAll("gridnodes") Set<Gridnode> gridnodes) {
		final Result result = RestCommandFixture.run(new GridnodeList(), Response.ok(gridnodes).build());

		assertThat(result.request(), equalTo(Optional.of(LISTING)));
		assertThat(MAPPER.readTree(result.out()), equalTo(MAPPER.readTree(MAPPER.writeValueAsString(gridnodes))));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void printsAnEmptyListWhenThereAreNoGridnodes() {
		final Result result = RestCommandFixture.run(new GridnodeList(), Response.noContent().build());

		assertThat(result.request(), equalTo(Optional.of(LISTING)));
		assertThat(result.out().lines().toList(), equalTo(List.of("[]")));
	}
}
