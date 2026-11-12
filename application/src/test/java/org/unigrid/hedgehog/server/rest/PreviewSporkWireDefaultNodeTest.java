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

package org.unigrid.hedgehog.server.rest;

import jakarta.ws.rs.core.Response.Status;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.server.BaseDefaultNodeSporkTest;

/* A holder of the REST token hands a node run without --preview the sporks of the parts that this release leaves off,
   signed by the board in the way that a node which took them would store: both co-signed, and as a proposal that waits
   for the second key. The node refuses each of them as content it does not take, and so stores none, holds none as a
   proposal, sends none on to the peer that reads every type, and does not note one as a sign that it is behind. A spork
   of this release handed over after them is taken all the same, which shows that the signing here is one the node
   accepts. */
public class PreviewSporkWireDefaultNodeTest extends BaseDefaultNodeSporkTest {
	@BeforeTry
	public void startWithoutTheSporks() {
		forgetTheSporks();
	}

	private static List<GridSpork> sporksOfThePreview() {
		final List<GridSpork> sporks = new ArrayList<>();

		OF_THE_PREVIEW.forEach(type -> {
			sporks.add(cosigned(type));
			sporks.add(proposed(type));
		});

		return sporks;
	}

	@Example
	public void shouldRefuseEverySporkOfThePreviewButTakeOneOfThisRelease() {
		final List<GridSpork> sporks = sporksOfThePreview();
		final GridSpork control = mintSupply();

		sporks.stream().filter(spork -> !spork.isPending()).forEach(this::assertWouldMakeANodeBehind);

		for (GridSpork spork : sporks) {
			final String which = spork.getType() + (spork.isPending() ? " proposed" : " co-signed");
			final ResponseOddityException refusal = assertThrows(ResponseOddityException.class, () -> submit(spork));

			assertThat(which, refusal.getMessage(), startsWith("400 "));
			assertThat(which, refusal.getBody(), containsString("This node takes no " + spork.getType() + " spork"));
		}

		assertNoSporkOfThePreviewTaken();

		assertThat(submit(control).getStatus(), equalTo(Status.OK.getStatusCode()));
		assertTakenAndSentOn(control);
		assertNoSporkOfThePreviewTaken();
	}
}
