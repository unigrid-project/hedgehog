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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.server.rest.StorageResource;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class FingerprintCommandTest {
	private static FingerprintCommand fingerprintCommand(String method) {
		return new FingerprintCommand(method) {
			@Override
			protected void execute(Response response) {
				System.out.println(response.getStatusInfo());
			}
		};
	}

	@Property(tries = 30)
	public void sendsTheFingerprintInAHeaderToTheStorage(@ForAll boolean get, @ForAll boolean longOption,
		@ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 64) String fingerprint) {

		final String method = get ? HttpMethod.GET : HttpMethod.DELETE;
		final FingerprintCommand command = fingerprintCommand(method);
		final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>(
			Map.of(StorageResource.FINGERPRINT_HEADER, fingerprint));

		new CommandLine(command).parseArgs(longOption ? "--fingerprint" : "-f", fingerprint);

		final Result result = RestCommandFixture.run(command, Response.ok().build());

		assertThat(result.request(), equalTo(Optional.of(new Request(method, "/storage", Optional.empty(),
			Optional.of(headers)))));
	}

	@Example
	public void requiresAFingerprint() {
		assertThrows(CommandLine.MissingParameterException.class, () -> new CommandLine(fingerprintCommand(
			HttpMethod.GET)).parseArgs());
	}
}
