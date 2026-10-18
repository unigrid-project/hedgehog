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

package org.unigrid.hedgehog.command.option;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;

@AddLifecycleHook(value = RestoreOptionsHook.class,propagateTo = PropagationMode.ALL_DESCENDANTS)
public class RestOptionsTest {
	@Provide
	public Arbitrary<List<String>> provideCommand() {
		return Arbitraries.of(List.of("cli"), List.of("cli", "stop"), List.of("cli", "node-list"), List.of("daemon"));
	}

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Provide
	public Arbitrary<String> provideNumberOption() {
		return Arbitraries.just("--restport");
	}

	@Property
	public void shouldTakeTheAddressItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll("provideHost") String host, @ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port,
		@ForAll boolean longNames) {

		OptionsCli.parse(command, longNames ? "--resthost" : "-R", host, longNames ? "--restport" : "-r",
			String.valueOf(port)
		);

		assertThat(RestOptions.getHost(), equalTo(host));
		assertThat(RestOptions.getPort(), equalTo(port));
	}

	@Property
	public void shouldTakeTheTokenItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll @AlphaChars @NumericChars @StringLength(min = 1, max = 64) String token) {

		OptionsCli.parse(command, "--resttoken", token);
		assertThat(RestOptions.getToken(), equalTo(token));
	}

	@Property
	public void shouldServeLocallyWithTheEnvironmentTokenByDefault(@ForAll("provideCommand") List<String> command) {
		OptionsCli.parse(command);

		assertThat(RestOptions.getHost(), equalTo("localhost"));
		assertThat(RestOptions.getPort(), equalTo(RestOptions.DEFAULT_PORT));
		assertThat(RestOptions.getToken(), equalTo(System.getenv("HEDGEHOG_REST_TOKEN")));
	}

	@Property
	public void shouldRefuseANumberThatIsNotOne(@ForAll("provideCommand") List<String> command,
		@ForAll("provideNumberOption") String option, @ForAll @AlphaChars @StringLength(min = 1, max = 10) String value) {

		assertThat(OptionsCli.refusal(command, option, value).getMessage(), containsString(option + "': '" + value + "'"));
	}
}
