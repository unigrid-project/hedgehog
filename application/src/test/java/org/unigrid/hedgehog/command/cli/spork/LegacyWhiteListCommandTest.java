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
import java.math.BigDecimal;
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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.submitted;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.AddressBalance;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LegacyWhiteListTest;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.plainFileSystem;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.write;
import picocli.CommandLine;
import picocli.CommandLine.IExitCodeGenerator;

/** The command the board makes the legacy whitelist with, from a file of addresses checked against the snapshot. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LegacyWhiteListCommandTest {
	private static final String NAME = "legacy-whitelist";
	private static final String LOCATION = "/gridspork/legacy-whitelist";
	private static final String FILE = "/lists/approved.txt";
	private static final String NOT_FUNDED = "hold no funds in the legacy snapshot";
	private static final int CAP = 4096;
	private static final String BALANCES = "/bootstrap/addresses";

	/** Signs with board keys the tests made, as the real ones are kept by the board. */
	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	/** Sporks as a node shows them, for the get command to print. */
	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	/** Lists of different hashes, short enough to check one address at a time. */
	@Provide
	public Arbitrary<List<String>> lists() {
		return LegacyWhiteListTest.hashes().list().uniqueElements().ofMinSize(1).ofMaxSize(30);
	}

	/** Words that no legacy address can be. */
	@Provide
	public Arbitrary<String> notAddresses() {
		return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
	}

	/** Makes the command read its file from the given file system and every other command as usual. */
	private static CommandLine.IFactory readingFrom(FileSystem fs) {
		return new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				return type == LegacyWhiteList.class ? type.cast(new LegacyWhiteList(fs))
					: CommandLine.defaultFactory().create(type);
			}
		};
	}

	/**
	 * The set command for the whitelist, signing with the given key. A node that holds no whitelist yet takes only the
	 * first version of it.
	 */
	private static Runnable set(FileSystem fs, String key) {
		return (Runnable) new CommandLine(new GridSporkSet(), readingFrom(fs)).parseArgs("-D", FILE, "-k", key, NAME,
			"--first-version").subcommand().commandSpec().userObject();
	}

	/** Runs the set command from the top of the command tree, as a board member types it. */
	private static HedgehogCli.Result setThroughTheTree(FileSystem fs) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, readingFrom(fs)),
			"cli", "gridspork-set", "-D", FILE, "-k", "0".repeat(64), NAME);
	}

	/** The legacy address of a hash160. */
	private static String address(String hash) {
		return LegacyAddress.encode(HexFormat.of().parseHex(hash));
	}

	/** What the snapshot holds for the address of a hash. */
	private static AddressBalance balance(String hash, String amount, int transactions) {
		return new AddressBalance(address(hash), new BigDecimal(amount), transactions);
	}

	/** A snapshot in which every address of the hashes holds funds. */
	private static AddressBalance[] funded(List<String> hashes) {
		return hashes.stream().map(hash -> balance(hash, "1", 1)).toArray(AddressBalance[]::new);
	}

	/** The file a board member writes, one address per line. */
	private static String listOf(List<String> hashes) {
		return String.join("\n", hashes.stream().map(LegacyWhiteListCommandTest::address).toList()) + "\n";
	}

	/** Where the command sent its requests, in order. */
	private static List<String> locationsOf(Result result) {
		return result.requests().stream().map(Request::location).toList();
	}

	/** The exit code a command ended with. */
	private static int exitCodeOf(Runnable command) {
		return ((IExitCodeGenerator) command).getExitCode();
	}

	/** The get command asks for the whole list and prints what the node answers. */
	@Property(tries = 10)
	public void getsAndPrintsTheWhitelist(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME),
			Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	/**
	 * A list of funded addresses is checked with the node and proposed as their hashes, comments and blank lines left
	 * out.
	 */
	@SneakyThrows
	@Property(tries = 15)
	public void proposesTheHashesOfFundedAddressesSignedHereWithTheKey(@ForAll("lists") List<String> hashes) {
		try (FileSystem fs = plainFileSystem()) {
			final FakeSporkNode node = new FakeSporkNode().knowing(funded(hashes));

			write(fs, FILE, "# Approved by the board\n\n" + listOf(hashes), null);

			final Result result = RestCommandFixture.run(set(fs, PROPOSER.getPrivateKey()), node);
			final GridSpork proposal = submitted(result);

			assertThat(result.err(), equalTo(""));
			assertThat(proposal.getType(), equalTo(Type.LEGACY_WHITELIST));
			assertThat(proposal.signerOfHead(proposal.getSignature()),
				equalTo(Optional.of(PROPOSER.getPublicKey())));
			assertThat(proposal.getData(), equalTo(LegacyWhiteListTest.with(Set.copyOf(hashes))));
		}
	}

	/**
	 * An address the snapshot holds as empty, but which the node still owes a mint, is funded as the node counts the
	 * mint, and so passes; the whole list is asked about in one request.
	 */
	@SneakyThrows
	@Example
	public void passesAnAddressThatIsEmptyInTheSnapshotButOwedAMint() {
		final String owed = "5".repeat(40);
		final String funded = "6".repeat(40);

		try (FileSystem fs = plainFileSystem()) {
			final FakeSporkNode node = new FakeSporkNode().knowing(balance(owed, "0", 3),
				balance(funded, "2", 1)).owing(address(owed), new BigDecimal("50"));

			write(fs, FILE, listOf(List.of(owed, funded)), null);

			final Result result = RestCommandFixture.run(set(fs, PROPOSER.getPrivateKey()), node);

			assertThat(result.err(), equalTo(""));
			assertThat(submitted(result).getData(), equalTo(LegacyWhiteListTest.with(Set.of(owed, funded))));
			assertThat(locationsOf(result).stream().filter(BALANCES::equals).count(), equalTo(1L));
		}
	}

	/**
	 * An address the snapshot does not know, one it knows as empty and one that never moved are all refused by name, and
	 * nothing is proposed.
	 */
	@SneakyThrows
	@Example
	public void refusesEveryAddressThatHoldsNoFundsInTheSnapshotByName() {
		final String funded = "1".repeat(40);
		final String empty = "2".repeat(40);
		final String unknown = "3".repeat(40);
		final String unmoved = "4".repeat(40);

		try (FileSystem fs = plainFileSystem()) {
			final FakeSporkNode node = new FakeSporkNode().knowing(balance(funded, "5", 2),
				balance(empty, "0", 7), balance(unmoved, "3", 0));
			final Runnable command = set(fs, PROPOSER.getPrivateKey());

			write(fs, FILE, listOf(List.of(funded, empty, unknown, unmoved)), null);

			final Result result = RestCommandFixture.run(command, node);

			assertThat(exitCodeOf(command), equalTo(ExitCode.FAILED));
			assertThat(result.err(), containsString(NOT_FUNDED));
			assertThat(result.err(), containsString(address(empty)));
			assertThat(result.err(), containsString(address(unknown)));
			assertThat(result.err(), containsString(address(unmoved)));
			assertThat(result.err(), not(containsString(address(funded))));
			assertThat(node.submitted(), equalTo(List.of()));
		}
	}

	/** Nothing is proposed when the node holds no snapshot, as no address can then be checked. */
	@SneakyThrows
	@Example
	public void refusesWhenTheNodeHoldsNoSnapshotToCheckAgainst() {
		final String hash = "1".repeat(40);

		try (FileSystem fs = plainFileSystem()) {
			final FakeSporkNode node = new FakeSporkNode().withoutASnapshot();
			final Runnable command = set(fs, PROPOSER.getPrivateKey());

			write(fs, FILE, listOf(List.of(hash)), null);

			final Result result = RestCommandFixture.run(command, node);

			assertThat(exitCodeOf(command), equalTo(ExitCode.FAILED));
			assertThat(result.err(), containsString("holds no legacy snapshot"));
			assertThat(node.submitted(), equalTo(List.of()));
		}
	}

	/** A line that is no legacy address is named by its number, without a stack trace. */
	@SneakyThrows
	@Property(tries = 20)
	public void namesTheLineOfTheAddressThatIsNone(@ForAll("lists") List<String> hashes,
		@ForAll("notAddresses") String bad, @ForAll @IntRange(min = 0, max = 30) int where) {

		try (FileSystem fs = plainFileSystem()) {
			final List<String> lines = new ArrayList<>(hashes.stream().map(LegacyWhiteListCommandTest::address)
				.toList());
			final int line = Math.min(where, lines.size());

			lines.add(line, bad);
			write(fs, FILE, String.join("\n", lines), null);

			final HedgehogCli.Result result = setThroughTheTree(fs);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(),
				containsString("Line " + (line + 1) + " of " + FILE + " is not a legacy address"));
			assertThat(result.out(), emptyString());
			result.assertNoStackTrace();
		}
	}

	/** A file of more addresses than the spork may hold is refused before the node is asked anything. */
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
}
