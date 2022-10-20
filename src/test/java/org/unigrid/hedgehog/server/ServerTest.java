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

import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;

public class ServerTest extends BaseServerTest {
	@Example
	public boolean shoulBeAbleTodStartMultipleServers() {
		for (TestServer s : servers) {
			new Expectations() {{
				int port = FreePortFinder.findFreeLocalPort();

				NetOptions.getHost(); result = "localhost";
				NetOptions.getPort(); result = port;
				RestOptions.getHost(); result = "localhost";
				RestOptions.getPort(); result = FreePortFinder.findFreeLocalPort(port + 1);
			}};
		}

		return true;
	}
}
