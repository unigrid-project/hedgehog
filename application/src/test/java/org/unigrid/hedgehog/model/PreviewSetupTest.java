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

package org.unigrid.hedgehog.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PreviewSetupTest {
	private static final List<String> DAEMON = List.of("daemon");
	private static final String GRIDNODE_KEY = "/keys/gridnode.key";

	public record Parked(String name, List<String> options) { }

	@Provide
	public Arbitrary<Parked> provideParked() {
		return Arbitraries.of(new Parked("--ledger", List.of("--ledger")),
			new Parked("--gridnode", List.of("-G", GRIDNODE_KEY)),
			new Parked("--gridnode", List.of("--gridnode", GRIDNODE_KEY)),
			new Parked("--socks-port", List.of("--socks-port", "1080"))
		);
	}

	@Provide
	public Arbitrary<List<String>> provideS3Options() {
		return Arbitraries.of(List.of("--no-s3"), List.of("--s3"), List.of("--s3host", "localhost", "--s3port", "9000",
			"--s3plain")
		);
	}

	private static void parse(List<String> options, String... more) {
		OptionsCli.parse(DAEMON, Stream.concat(options.stream(), Stream.of(more)).toArray(String[]::new));
	}

	private static String messageOfValidating() {
		return assertThrows(IllegalArgumentException.class, PreviewSetup::validate).getMessage();
	}

	@Example
	public void shouldAcceptADaemonWithoutOptions() {
		OptionsCli.parse(DAEMON);

		PreviewSetup.validate();
	}

	@Property
	public void shouldRefuseEachOptionOfThePartsThisReleaseLeavesOff(@ForAll("provideParked") Parked parked) {
		parse(parked.options());

		assertThat(messageOfValidating(), equalTo(parked.name() + " is not part of this release"));
	}

	@Property
	public void shouldAcceptEachOptionOfThePartsThisReleaseLeavesOffWithThePreview(
		@ForAll("provideParked") Parked parked) {

		parse(parked.options(), "--preview");

		PreviewSetup.validate();
	}

	@Property(tries = 30)
	public void shouldRefuseEverySocksPortButNone(@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port) {
		OptionsCli.parse(DAEMON, "--socks-port", String.valueOf(port));

		assertThat(messageOfValidating(), equalTo("--socks-port is not part of this release"));
	}

	@Example
	public void shouldAcceptNoSocksPort() {
		OptionsCli.parse(DAEMON, "--socks-port", "0");

		PreviewSetup.validate();
	}

	/* A node that was started with them before still starts */
	@Property
	public void shouldAcceptTheS3Options(@ForAll("provideS3Options") List<String> options) {
		parse(options);

		PreviewSetup.validate();
	}
}
