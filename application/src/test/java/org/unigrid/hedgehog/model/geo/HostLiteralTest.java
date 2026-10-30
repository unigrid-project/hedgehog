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

package org.unigrid.hedgehog.model.geo;

import java.net.InetAddress;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class HostLiteralTest {
	@Example
	public void readsTheLiteralOfAHostWithOrWithoutItsPort() {
		assertThat(HostLiteral.of("89.160.20.112:52883"), equalTo(Optional.of(InetAddress.ofLiteral("89.160.20.112"))));
		assertThat(HostLiteral.of("81.2.69.160"), equalTo(Optional.of(InetAddress.ofLiteral("81.2.69.160"))));
		assertThat(HostLiteral.of("[2001:218::1]:52883"), equalTo(Optional.of(InetAddress.ofLiteral("2001:218::1"))));
	}

	/* A name is never resolved */
	@Example
	public void readsNothingOfAHostName() {
		assertThat(HostLiteral.of("gn.example.org:52883"), equalTo(Optional.empty()));
		assertThat(HostLiteral.of("localhost:52883"), equalTo(Optional.empty()));
		assertThat(HostLiteral.of("[gn.example.org]:52883"), equalTo(Optional.empty()));
		assertThat(HostLiteral.of("[2001:218::1"), equalTo(Optional.empty()));
		assertThat(HostLiteral.of(""), equalTo(Optional.empty()));
		assertThat(HostLiteral.of(null), equalTo(Optional.empty()));
	}
}
