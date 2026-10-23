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

package org.unigrid.hedgehog.command.cli.spork;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.bitcoinj.base.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklistTest;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.plainFileSystem;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.write;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerBlacklistCommandTest {
	private static final String NAME = "ledger-blacklist";
	private static final String LOCATION = "/gridspork/ledger-blacklist";
	private static final String FILE = "/lists/blacklist.txt";
	private static final int CAP = 4096;

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<List<String>> lists() {
		return LedgerBlacklistTest.hashes().list().uniqueElements().ofMaxSize(30);
	}

	@Provide
	public Arbitrary<String> notAddresses() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
	}

	private static CommandLine.IFactory readingFrom(FileSystem fs) {
		return new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				return type == LedgerBlacklist.class ? type.cast(new LedgerBlacklist(fs))
					: CommandLine.defaultFactory().create(type);
			}
		};
	}

	private static Runnable set(FileSystem fs, String key) {
		return (Runnable) new CommandLine(new GridSporkSet(), readingFrom(fs)).parseArgs("-D", FILE, "-k", key, NAME)
			.subcommand().commandSpec().userObject();
	}

	private static HedgehogCli.Result setThroughTheTree(FileSystem fs) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, readingFrom(fs)),
			"cli", "gridspork-set", "-D", FILE, "-k", "0".repeat(64), NAME);
	}

	private static String address(String hash) {
		return LegacyAddress.encode(HexFormat.of().parseHex(hash));
	}

	/* Everything a hand-kept list holds that is no address: blank lines, remarks, padding and a remark per line */
	private static String decorated(List<String> hashes) {
		final List<String> lines = new ArrayList<>(List.of("# Held back from claims", "", "   "));

		hashes.forEach(hash -> lines.addAll(List.of("  " + address(hash) + "  # listed by the board", "", "# --")));
		return String.join("\n", lines) + "\n";
	}

	@Property(tries = 10)
	public void getsAndPrintsTheBlacklist(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@SneakyThrows
	@Property(tries = 15)
	public void proposesTheHashesOfTheListedAddressesSignedHereWithTheKey(@ForAll("lists") List<String> hashes) {
		try (FileSystem fs = plainFileSystem()) {
			final FakeSporkNode node = new FakeSporkNode();

			write(fs, FILE, decorated(hashes), null);

			assertThat(proposed(RestCommandFixture.run(set(fs, PROPOSER.getPrivateKey()), node), node,
				Type.LEDGER_BLACKLIST).getData(), equalTo(LedgerBlacklistTest.with(Set.copyOf(hashes))));
		}
	}

	@SneakyThrows
	@Property(tries = 20)
	public void namesTheLineOfTheAddressThatIsNone(@ForAll("lists") List<String> hashes,
		@ForAll("notAddresses") String bad, @ForAll @IntRange(min = 0, max = 30) int where) {

		try (FileSystem fs = plainFileSystem()) {
			final List<String> lines = new ArrayList<>(hashes.stream().map(LedgerBlacklistCommandTest::address).toList());
			final int line = Math.min(where, lines.size());

			lines.add(line, bad);
			write(fs, FILE, String.join("\n", lines), null);

			final HedgehogCli.Result result = setThroughTheTree(fs);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Line " + (line + 1) + " of " + FILE + " is not a legacy address"));
			assertThat(result.out(), emptyString());
			result.assertNoStackTrace();
		}
	}

	@SneakyThrows
	@Example
	public void refusesAnAddressOfAnotherNetwork() {
		final String foreign = Base58.encodeChecked(0, new byte[20]);

		try (FileSystem fs = plainFileSystem()) {
			write(fs, FILE, "# list\n\n" + foreign + "\n", null);

			final HedgehogCli.Result result = setThroughTheTree(fs);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Line 3 of " + FILE));
			assertThat(result.err(), containsString("not for this network"));
		}
	}

	@SneakyThrows
	@Example
	public void refusesAListLongerThanTheSporkHolds() {
		final List<String> lines = IntStream.rangeClosed(0, CAP).mapToObj(index -> address("%040x".formatted(index)))
			.toList();

		try (FileSystem fs = plainFileSystem()) {
			write(fs, FILE, String.join("\n", lines), null);

			final HedgehogCli.Result result = setThroughTheTree(fs);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("at most " + CAP));
			result.assertNoStackTrace();
		}
	}

	@SneakyThrows
	@Example
	public void namesAFileItCannotRead() {
		try (FileSystem fs = plainFileSystem()) {
			final HedgehogCli.Result result = setThroughTheTree(fs);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot read the legacy addresses from " + FILE));
			assertThat(result.out(), emptyString());
			result.assertNoStackTrace();
		}
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new LedgerBlacklist()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
