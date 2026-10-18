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

package org.unigrid.hedgehog.model.gridnode;

import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GridnodeSetupTest {
	private static String failureOf(String host, int port, String announce) {
		try {
			GridnodeSetup.announcedAddress(host, port, Optional.ofNullable(announce));
			return "";
		} catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
	}

	@Example
	public void shouldAnnounceASpecificBindAddress() {
		assertThat(GridnodeSetup.announcedAddress("127.0.0.1", 5000, Optional.empty()), is("127.0.0.1:5000"));
		assertThat(GridnodeSetup.announcedAddress("::1", 5000, Optional.empty()), is("[::1]:5000"));
		assertThat(GridnodeSetup.announcedAddress("node.example.org", 52883, Optional.empty()),
			is("node.example.org:52883"));
	}

	@Example
	public void shouldRefuseAWildcardBindWithoutAnAnnounceAddress() {
		for (String wildcard : new String[] { "0.0.0.0", "::", "[::]", "" }) {
			assertThat(failureOf(wildcard, 5000, null), containsString("--announce-address"));
		}
	}

	@Example
	public void shouldPreferTheAnnounceAddress() {
		assertThat(GridnodeSetup.announcedAddress("0.0.0.0", 5000, Optional.of("203.0.113.7:6000")),
			is("203.0.113.7:6000"));
		assertThat(GridnodeSetup.announcedAddress("0.0.0.0", 5000, Optional.of(" [2001:db8::1]:6000 ")),
			is("[2001:db8::1]:6000"));
	}

	@Example
	public void shouldRefuseAnAnnounceAddressThatNobodyCanReach() {
		for (String bad : new String[] { "host", "host:0", "host:65536", "host:abc", ":5000", "0.0.0.0:5000",
			"[::]:5000" }) {

			assertThat(bad, failureOf("127.0.0.1", 5000, bad), containsString("--announce-address"));
		}
	}
}
