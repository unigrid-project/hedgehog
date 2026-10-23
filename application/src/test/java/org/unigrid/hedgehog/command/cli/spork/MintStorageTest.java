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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MintStorageTest {
	private static final String NAME = "mint-storage";
	private static final String LOCATION = "/gridspork/mint-storage";
	private static final int ACCOUNT_LENGTH = 64;
	private static final int HASH_LENGTH = 20;
	private static final long UNSIGNED_MAXIMUM = 4_294_967_295L;
	private static final String ACCOUNT = "ab".repeat(ACCOUNT_LENGTH / 2);
	private static final String OLD = LegacyAddress.encode(new byte[HASH_LENGTH]);
	private static final String AMOUNT = "5";
	private static final String UNSPECIFIED = "Both the address and its block height or time have to be specified";

	@Provide
	public Arbitrary<String> amounts() {
		return SporkCommands.amounts();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<String> accounts() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(ACCOUNT_LENGTH);
	}

	@Provide
	public Arbitrary<String> oldAddresses() {
		return Arbitraries.bytes().array(byte[].class).ofSize(HASH_LENGTH).map(LegacyAddress::encode);
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	/* Most of what is tested here is about the number, and a time that has passed is allowed for it */
	private static Runnable grow(String option, String amount, Optional<String> address, Optional<String> number) {
		return growCommand(true, option, amount, address, number);
	}

	private static Runnable growCommand(boolean allowPast, String option, String amount, Optional<String> address,
		Optional<String> number) {

		final Stream<String> mint = Stream.concat(address.stream().flatMap(value -> Stream.of("--address", value)),
			number.stream().flatMap(value -> Stream.of(option, value)));
		final Stream<String> leave = allowPast ? Stream.of("--allow-past") : Stream.empty();

		return subcommand(new GridSporkGrow(), Stream.concat(Stream.of("-D", amount, "-k", PROPOSER.getPrivateKey(),
			NAME, "--first-version"), Stream.concat(leave, mint)).toArray(String[]::new));
	}

	/* A node can say it holds nothing, and a first version takes the place of any other however old, so one is grown
	   only when the operator says so */
	@Example
	public void growsAFirstVersionOnlyWhenToldTo() {
		final FakeSporkNode node = new FakeSporkNode();

		assertThat(assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(subcommand(
			new GridSporkGrow(), "-D", AMOUNT, "-k", PROPOSER.getPrivateKey(), NAME, "--address", ACCOUNT, "--time",
			"4102444800"), node)).getMessage(), allOf(containsString("The node holds no MINT_STORAGE spork"),
			containsString("--first-version")));
		assertThat(node.submitted(), empty());
	}

	private static Map<Location, BigDecimal> mintsOf(Result result) {
		return SporkCommands.submitted(result).<SporkData>getData().getMints();
	}

	private static Map<Location, BigDecimal> mint(String address, String number) {
		return Map.of(new Location(new Address(address), Integer.parseUnsignedInt(number)), new BigDecimal(AMOUNT));
	}

	private static Map<Location, BigDecimal> sent(String option, String address, String number) {
		return mintsOf(RestCommandFixture.run(grow(option, AMOUNT, Optional.of(address), Optional.of(number)),
			new FakeSporkNode()));
	}

	private static Map<Location, BigDecimal> sentWithoutLeave(String address, String number) {
		return mintsOf(RestCommandFixture.run(growCommand(false, "--time", AMOUNT, Optional.of(address),
			Optional.of(number)), new FakeSporkNode()));
	}

	private static String refusal(String address, String number) {
		return SporkCommands.refusal(grow("--height", AMOUNT, Optional.of(address), Optional.of(number)));
	}

	private static String refusalWithoutLeave(String address, String number) {
		return SporkCommands.refusal(growCommand(false, "--time", AMOUNT, Optional.of(address), Optional.of(number)));
	}

	@Property(tries = 10)
	public void getsAndPrintsTheMintStorage(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void proposesTheMintBesideTheStoredOnesSignedHereWithTheKey(@ForAll("amounts") String amount,
		@ForAll("oldAddresses") String address, @ForAll @Positive int height) {

		final SporkData before = new SporkData();

		before.setMints(new HashMap<>(Map.of(new Location(new Address(OLD), 1), BigDecimal.ONE)));

		final GridSpork stored = stored(Type.MINT_STORAGE, before);
		final FakeSporkNode node = new FakeSporkNode().holding(stored);
		final Result result = RestCommandFixture.run(grow("--height", amount, Optional.of(address),
			Optional.of(Integer.toString(height))), node);
		final Map<Location, BigDecimal> expected = new HashMap<>(before.getMints());

		expected.put(new Location(new Address(address), height), new BigDecimal(amount));
		assertThat(proposed(result, node, Type.MINT_STORAGE).<SporkData>getData().getMints(), equalTo(expected));
	}

	@Property(tries = 5)
	public void sendsNothingWithoutAnAddress(@ForAll("amounts") String amount, @ForAll Optional<@Positive Integer> height) {
		final Result result = RestCommandFixture.run(grow("--height", amount, Optional.empty(),
			height.map(String::valueOf)), Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out().lines().toList(), equalTo(List.of(UNSPECIFIED)));
	}

	@Property(tries = 5)
	public void sendsNothingWithoutAHeightOrATime(@ForAll("amounts") String amount,
		@ForAll("accounts") String address) {

		final Result result = RestCommandFixture.run(grow("--height", amount, Optional.of(address), Optional.empty()),
			Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out().lines().toList(), equalTo(List.of(UNSPECIFIED)));
	}

	@Property(tries = 10)
	public void proposesTheSecondsOfAnAccountThatAreGivenAsDigits(@ForAll("accounts") String account,
		@ForAll @LongRange(min = 0, max = UNSIGNED_MAXIMUM) long seconds) {

		for (String option : List.of("--height", "--time")) {
			assertThat(sent(option, account, Long.toString(seconds)), equalTo(mint(account, Long.toString(seconds))));
		}
	}

	@Example
	public void proposesTheSecondsOfAnAccountAtTheEdgesOfThirtyTwoBits() {
		for (String seconds : List.of("0", "1", "2147483647", "2147483648", "4000000000", "4294967295")) {
			assertThat(sent("--time", ACCOUNT, seconds), equalTo(mint(ACCOUNT, seconds)));
		}
	}

	@Example
	public void turnsAnIsoInstantOfAnAccountIntoSeconds() {
		final Map<String, String> instants = Map.of(
			"2026-06-01T00:00:00Z", "1780272000", "2026-06-01T02:00:00+02:00", "1780272000",
			"2026-05-31T22:00:00-02:00", "1780272000", "2026-06-01T00:00:00+05:30", "1780252200",
			"1970-01-01T00:00:00Z", "0", "2106-02-07T06:28:15Z", "4294967295", "2038-01-19T03:14:08Z", "2147483648",
			"2026-06-01T00:00:00.9Z", "1780272000");

		instants.forEach((instant, seconds) -> {
			assertThat(instant, sent("--time", ACCOUNT, instant), equalTo(mint(ACCOUNT, seconds)));
			assertThat(instant, sent("--height", ACCOUNT, instant), equalTo(mint(ACCOUNT, seconds)));
		});
	}

	@Example
	public void refusesAnIsoInstantWithoutAZone() {
		for (String instant : List.of("2026-06-01T00:00:00", "2026-06-01", "2026-06-01T00:00", "June 1st")) {
			final String message = refusal(ACCOUNT, instant);

			assertThat(message, containsString(instant));
			assertThat(message, containsString("ISO-8601"));
			assertThat(message, containsString("zone"));
		}
	}

	@Example
	public void refusesATimeOutsideOf1970To2106() {
		for (String time : List.of("1969-12-31T23:59:59Z", "2106-02-07T06:28:16Z", "9999-12-31T23:59:59Z",
			"4294967296", "99999999999", "999999999999999999")) {

			final String message = refusal(ACCOUNT, time);

			assertThat(time, message, containsString("ledger account"));
			assertThat(time, message, containsString("4294967295"));
		}
	}

	@Example
	public void refusesWhatIsNeitherDigitsNorAnInstant() {
		for (String text : List.of("-1", "+1", "1.5", "0x10", "1e3", "", " ", "12345678901234567890123")) {
			assertThat(text, refusal(ACCOUNT, text), containsString("digits"));
		}
	}

	@Property(tries = 10)
	public void refusesAnIsoInstantForAnAddressOfTheOldChain(@ForAll("oldAddresses") String address) {
		for (String instant : List.of("2026-06-01T00:00:00Z", "2026-06-01T02:00:00+02:00", "2026-06-01T00:00:00")) {
			final String message = refusal(address, instant);

			assertThat(message, containsString("old-style"));
			assertThat(message, containsString("height"));
			assertThat(message, containsString(instant));
		}
	}

	/* What is not a lower-case account takes a height alone, and is then no address that the ledger can mint to */
	@Example
	public void takesOnlyAHeightForWhatIsNotALowerCaseAccountAndRefusesToMintToIt() {
		for (String address : List.of(ACCOUNT.toUpperCase(), "garbage", "abc" + ACCOUNT, ACCOUNT.substring(1))) {
			final FakeSporkNode node = new FakeSporkNode();

			assertThat(address, refusal(address, "2026-06-01T00:00:00Z"), containsString("height"));
			assertThat(address, assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(
				grow("--height", AMOUNT, Optional.of(address), Optional.of("4200000")), node)).getMessage(),
				containsString("neither"));
			assertThat(address, node.submitted(), empty());
		}
	}

	@Property(tries = 10)
	public void proposesTheHeightOfTheOldChainOfAnOldAddressAsDigits(@ForAll("oldAddresses") String address,
		@ForAll @IntRange(min = 0) int height) {

		assertThat(sent("--height", address, Integer.toString(height)), equalTo(mint(address,
			Integer.toString(height))));
	}

	@Example
	public void refusesAHeightOfTheOldChainAbove2To31() {
		for (String height : List.of("2147483648", "4000000000", "4294967295")) {
			final String message = refusal(OLD, height);

			assertThat(message, containsString("old-style"));
			assertThat(message, containsString(height));
		}
	}

	@Example
	public void refusesAnAmountThatNoNodeCanMintBeforeSigningIt() {
		for (String amount : List.of("0", "0.000000001", "92233720369", "five")) {
			final FakeSporkNode node = new FakeSporkNode();

			assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(grow("--time", amount,
				Optional.of(ACCOUNT), Optional.of("4102444800")), node));
			assertThat(amount, node.submitted(), empty());
		}
	}

	/* A time in digits is seconds since 1970: 20260601 is the 23rd of August 1970, not the 1st of June 2026 */
	@Example
	public void refusesATimeThatHasPassedAndSaysWhatTheDigitsMean() {
		final String message = refusalWithoutLeave(ACCOUNT, "20260601");

		assertThat(message, containsString("20260601"));
		assertThat(message, containsString("1970-08-23"));
		assertThat(message, containsString("seconds since 1970"));
		assertThat(message, containsString("2026-06-01T00:00:00Z"));
		assertThat(message, containsString("--allow-past"));
	}

	@Example
	public void refusesAnInstantThatHasPassed() {
		final String message = refusalWithoutLeave(ACCOUNT, "2026-06-01T00:00:00Z");

		assertThat(message, containsString("has already passed"));
		assertThat(message, containsString("--allow-past"));
	}

	@Example
	public void takesATimeThatIsToComeWithoutLeave() {
		assertThat(sentWithoutLeave(ACCOUNT, "2100-01-01T00:00:00Z"), equalTo(mint(ACCOUNT, "4102444800")));
		assertThat(sentWithoutLeave(ACCOUNT, "4102444800"), equalTo(mint(ACCOUNT, "4102444800")));
	}

	@Example
	public void takesATimeThatHasPassedWhenItIsAllowed() {
		assertThat(sent("--time", ACCOUNT, "20260601"), equalTo(mint(ACCOUNT, "20260601")));
		assertThat(sent("--time", ACCOUNT, "2026-06-01T00:00:00Z"), equalTo(mint(ACCOUNT, "1780272000")));
	}

	@Property(tries = 10)
	public void needsNoLeaveForAHeightOfTheOldChain(@ForAll("oldAddresses") String address,
		@ForAll @IntRange(min = 0) int height) {

		assertThat(sentWithoutLeave(address, Integer.toString(height)), equalTo(mint(address,
			Integer.toString(height))));
	}

	@Example
	public void describesBothFormsOfTheNumberInTheHelp() {
		final CommandSpec spec = new CommandLine(new MintStorage()).getCommandSpec();
		final String number = String.join(" ", spec.findOption("--time").description());
		final String address = String.join(" ", spec.findOption("--address").description());

		assertThat(spec.findOption("--height"), sameInstance(spec.findOption("--time")));
		assertThat(number, containsString("block height"));
		assertThat(number, containsString("epoch seconds"));
		assertThat(number, containsString("ISO-8601"));
		assertThat(address, containsString("64 lower-case hex"));
		assertThat(String.join(" ", spec.findOption("--allow-past").description()), containsString("at once"));
	}

	@Example
	public void requiresAnAmountAndABoardKeyToGrow() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkGrow(), "-k", "key", NAME));

		assertThat(SporkCommands.refusal(subcommand(new GridSporkGrow(), "-D", "1", NAME, "--address", ACCOUNT,
			"--time", "4102444800")), containsString("A board key is needed"));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new MintStorage()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
