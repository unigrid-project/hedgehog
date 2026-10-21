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

import jakarta.ws.rs.core.Response;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;

/* The client trusts any certificate, so a private key of a network key is sent only to a node on this machine */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeyGuardTest {
	private static final String REMOTE = "203.0.113.9";
	private static final String DIGEST = "ab".repeat(32);
	private static final String KEY = "00".repeat(32);

	private static GridSporkCosign cosign() {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs("-k", KEY, DIGEST);
		return command;
	}

	@Example
	public void sendsNothingWhenAKeyWouldGoToAnotherMachine() {
		final Result result = RestCommandFixture.runOn(REMOTE, cosign(), Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.request(), equalTo(Optional.empty()));
		assertThat(result.err(), containsString("Refusing to send a private key to " + REMOTE));
		assertThat(result.err(), not(containsString(KEY)));
	}

	@Example
	public void sendsTheKeyToANodeOnThisMachine() {
		final Result result = RestCommandFixture.runOn("127.0.0.1", cosign(), Response.ok().build());

		assertThat(result.request().isPresent(), is(true));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void leavesACommandWithoutAKeyFreeToAskAnotherMachine() {
		final Result result = RestCommandFixture.runOn(REMOTE, new GridSporkPending(), Response.noContent().build());

		assertThat(result.request().isPresent(), is(true));
	}

	@Example
	public void takesOnlyAnAddressOfThisMachineForThisMachine() {
		assertThat(RestClientCommand.isLoopback("localhost"), is(true));
		assertThat(RestClientCommand.isLoopback("::1"), is(true));
		assertThat(RestClientCommand.isLoopback(REMOTE), is(false));
		assertThat(RestClientCommand.isLoopback("unresolvable.invalid"), is(false));
	}
}
