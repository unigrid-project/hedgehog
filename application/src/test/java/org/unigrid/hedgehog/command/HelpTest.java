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
import static org.hamcrest.Matchers.equalTo;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/* The commands and options of the parts that this release leaves off still run, but the help of their group leaves
   them out. A name counts as listed where it starts a line of the help, as the names of a group and of its options
   do, and not where it is part of another name or of a description. */
public class HelpTest {
	private static final List<String> PARKED_SPORKS = List.of("storage", "ledger-fee", "ledger-blacklist",
		"communication");

	static final Map<String, List<String>> LEFT_OUT = Map.of(
		"", List.of("s3"),
		"cli", List.of("ledger", "storage-put", "storage-get", "storage-delete", "gridnode-start", "gridnode-stop",
			"gridnode-list"
		),
		"util", List.of("ledger-key-generate", "ledger-genesis"),
		"cli gridspork-set", PARKED_SPORKS,
		"cli gridspork-get", PARKED_SPORKS,
		"daemon", List.of("--ledger", "--ledger-key", "-G", "--gridnode", "--socks-port", "--no-s3", "--s3port",
			"--preview"
		)
	);

	private static final Map<String, List<String>> LISTED = Map.of(
		"", List.of("bootstrap", "cli", "daemon", "util"),
		"cli", List.of("gridspork-get", "gridspork-set", "gridspork-grow", "gridspork-list", "gridspork-log",
			"gridspork-renew", "gridspork-pending", "gridspork-cosign", "node-add", "node-remove", "node-list", "stop"
		),
		"util", List.of("key-generate", "key-sign", "key-validate", "spork-db", "board-keys"),
		"cli gridspork-set", List.of("mint-supply", "board-keys"),
		"cli gridspork-get", List.of("mint-supply", "mint-storage", "board-keys"),
		"daemon", List.of("--restport", "--resttoken", "--netmaxinbound")
	);

	private static String usageOf(String group) {
		final String[] words = Stream.concat(Stream.of(group.split(" ")).filter(word -> !word.isEmpty()),
			Stream.of("--help")).toArray(String[]::new);

		final HedgehogCli.Result help = HedgehogCli.run(words);

		assertThat(help.err(), help.exitCode(), equalTo(0));
		return help.out();
	}

	private static boolean isListed(String usage, String name) {
		return Pattern.compile("(?m)^\\s+(?:\\S+, )*" + Pattern.quote(name) + "(?=[\\s,=\\[]|$)").matcher(usage)
			.find();
	}

	@Provide
	public Arbitrary<String> provideGroup() {
		return Arbitraries.of(LEFT_OUT.keySet());
	}

	@Property
	public void shouldLeaveThePartsOfThisReleaseOutOfTheHelp(@ForAll("provideGroup") String group) {
		final String usage = usageOf(group);

		LEFT_OUT.get(group).forEach(name -> assertThat(name + " in\n" + usage, isListed(usage, name),
			equalTo(false)));
	}

	@Property
	public void shouldListTheRestOfTheGroup(@ForAll("provideGroup") String group) {
		final String usage = usageOf(group);

		LISTED.get(group).forEach(name -> assertThat(name + " in\n" + usage, isListed(usage, name), equalTo(true)));
	}
}
