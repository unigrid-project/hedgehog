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

package org.unigrid.hedgehog.server;

import java.net.BindException;
import java.nio.channels.UnresolvedAddressException;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.option.OptionsCli;

public class ListenExceptionTest {
	@Property(tries = 50)
	public void shouldNameTheAddressTheServerAndTheOptionsBeforeTheReason(
		@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port,
		@ForAll @AlphaChars @StringLength(min = 1, max = 40) String reason) {

		final BindException cause = new BindException(reason);
		final ListenException failure = new ListenException("localhost", port, "REST", "--resthost, --restport", cause);

		assertThat(failure.getMessage(), equalTo("Cannot listen on localhost:" + port
			+ " for REST (--resthost, --restport): " + reason));

		assertThat(failure.getCause(), sameInstance(cause));
	}

	/* The colons of an IPv6 address would run into the one before the port */
	@Example
	public void shouldPutAnIpv6AddressInBrackets() {
		final ListenException failure = new ListenException("::1", 52883, "P2P", "--nethost, --netport",
			new BindException("Address already in use"));

		assertThat(failure.getMessage(),
			equalTo("Cannot listen on [::1]:52883 for P2P (--nethost, --netport): Address already in use"));
	}

	/* A host that no address is found for fails the bind with an exception that says nothing */
	@Example
	public void shouldTellAFailureWithoutAMessageByItsClass() {
		final ListenException failure = new ListenException("nowhere.invalid", 52884, "REST",
			"--resthost, --restport", new UnresolvedAddressException());

		assertThat(failure.getMessage(), equalTo("Cannot listen on nowhere.invalid:52884 for REST "
			+ "(--resthost, --restport): UnresolvedAddressException"));
	}
}
