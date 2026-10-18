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

package org.unigrid.hedgehog.command;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.unigrid.hedgehog.Hedgehog;
import picocli.CommandLine;

/* Bootstrap, CLI and Util only group their subcommands, so on their own they have nothing to run */
public class CommandGroupTest {
	private static final Map<String, List<String>> GROUPS = Map.of(
		"bootstrap", List.of("import", "info", "balance", "history", "sign", "fetch"),
		"cli", List.of("gridspork-get", "gridspork-grow", "gridspork-set", "gridspork-list", "gridspork-log",
			"gridspork-renew", "gridspork-pending", "gridspork-cosign", "node-add", "node-remove", "node-list",
			"gridnode-start", "gridnode-stop", "gridnode-list", "storage-put", "storage-get", "storage-delete", "stop"
		),
		"util", List.of("key-generate", "key-sign", "key-validate")
	);

	@Provide
	public Arbitrary<String> provideGroup() {
		return Arbitraries.of(GROUPS.keySet());
	}

	@Property
	public void shouldRegisterExactlyItsSubcommands(@ForAll("provideGroup") String group) {
		assertThat(new CommandLine(Hedgehog.class).getSubcommands().get(group).getSubcommands().keySet(),
			equalTo(Set.copyOf(GROUPS.get(group)))
		);
	}

	@Property
	public void shouldListEverySubcommandInItsHelp(@ForAll("provideGroup") String group, @ForAll boolean longName) {
		final HedgehogCli.Result result = HedgehogCli.run(group, longName ? "--help" : "-h");

		assertThat(result.exitCode(), equalTo(0));
		GROUPS.get(group).forEach(subcommand -> assertThat(result.out(), containsString(" " + subcommand)));
	}

	@Property
	public void shouldAskForASubcommand(@ForAll("provideGroup") String group) {
		final HedgehogCli.Result result = HedgehogCli.run(group);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Missing required subcommand"));
		result.assertNoStackTrace();
	}

	@Property(tries = 100)
	public void shouldRefuseAnUnknownSubcommand(@ForAll("provideGroup") String group,
		@ForAll @AlphaChars @StringLength(min = 1, max = 20) String name) {

		Assume.that(!GROUPS.get(group).contains(name));

		final HedgehogCli.Result result = HedgehogCli.run(group, name);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Unmatched argument at index 1: '" + name + "'"));
		result.assertNoStackTrace();
	}
}
