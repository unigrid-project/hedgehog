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

import jakarta.ws.rs.client.Entity;
import lombok.SneakyThrows;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.option.GridnodeOptions;

public class GridnodeResourceWithoutKeyTest extends BaseRestClientTest {
	@Mocked private GridnodeOptions gridnodeOptions;

	/* A mocked option would otherwise hand out a mocked path instead of none */
	@Override
	@BeforeProperty
	public void before() {
		new Expectations() {{
			GridnodeOptions.getGridnodeKeyFile(); result = null; minTimes = 0;
		}};

		super.before();
	}

	@Example
	@SneakyThrows
	public void shouldRefuseToActivateWithoutAGridnodeKey() {
		assertThat(client.put("/gridnode/start", Entity.text("")).getStatus(), is(409));
		assertThat(client.put("/gridnode/stop", Entity.text("")).getStatus(), is(409));
	}
}
