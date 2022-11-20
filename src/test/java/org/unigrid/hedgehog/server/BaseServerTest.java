/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server;

import jakarta.inject.Inject;
import java.util.List;
import mockit.Mocked;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.Instances;
import org.unigrid.hedgehog.jqwik.WeldSetup;

@WeldSetup(TestServer.class)
public class BaseServerTest extends BaseMockedWeldTest {
	private static final int NUM_SERVERS = 10;

	@Mocked
	protected NetOptions netOptions;

	@Mocked
	protected RestOptions restOptions;

	@Inject @Instances(NUM_SERVERS)
	protected List<TestServer> servers;

	@Provide
	public Arbitrary<List<TestServer>> provideTestServers(@ForAll @IntRange(min = 0, max = NUM_SERVERS - 1) int from,
		@ForAll @IntRange(min = 1, max = NUM_SERVERS) int num) {

		return Arbitraries.shuffle(servers.subList(from, Math.min(from + num, NUM_SERVERS - 1)));
	}

	@BeforeTry
	public void before() {
		for (TestServer s : servers) {
			TestServer.mockProperties(s);
		}
	}
}
