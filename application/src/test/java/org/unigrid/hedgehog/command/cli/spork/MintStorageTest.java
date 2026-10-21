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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.signedBy;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MintStorageTest {
	private static final String NAME = "mint-storage";
	private static final String LOCATION = "/gridspork/mint-storage";
	private static final int ADDRESS_LENGTH = 40;
	private static final int ACCOUNT_LENGTH = 64;
	private static final int HASH_LENGTH = 20;
	private static final long UNSIGNED_MAXIMUM = 4_294_967_295L;
	private static final String ACCOUNT = "ab".repeat(ACCOUNT_LENGTH / 2);
	private static final String UNSPECIFIED = "Both the address and its block height or time have to be specified";

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<String> amounts() {
		return SporkCommands.amounts();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@Provide
	public Arbitrary<String> addresses() {
		return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(ADDRESS_LENGTH);
	}

	@Provide
	public Arbitrary<String> accounts() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(ACCOUNT_LENGTH);
	}

	@Provide
	public Arbitrary<String> oldAddresses() {
		return Arbitraries.bytes().array(byte[].class).ofSize(HASH_LENGTH).map(LegacyAddress::encode);
	}

	private static Runnable grow(String amount, String key, Optional<String> address, Optional<String> number) {
		return growWith("--height", amount, key, address, number);
	}

	/* Most of what is tested here is about the number, and a time that has passed is allowed for it */
	private static Runnable growWith(String option, String amount, String key, Optional<String> address,
		Optional<String> number) {

		return growCommand(true, option, amount, key, address, number);
	}

	private static Runnable growCommand(boolean allowPast, String option, String amount, String key,
		Optional<String> address, Optional<String> number) {

		final Stream<String> mint = Stream.concat(address.stream().flatMap(value -> Stream.of("--address", value)),
			number.stream().flatMap(value -> Stream.of(option, value)));
		final Stream<String> leave = allowPast ? Stream.of("--allow-past") : Stream.empty();

		return subcommand(new GridSporkGrow(), Stream.concat(Stream.of("-D", amount, "-k", key, NAME),
			Stream.concat(leave, mint)).toArray(String[]::new));
	}

	private static Result sentWithoutLeave(String address, String number) {
		return RestCommandFixture.run(growCommand(false, "--time", "5", "key", Optional.of(address),
			Optional.of(number)), Response.ok("{}").build());
	}

	private static String refusalWithoutLeave(String address, String number) {
		final AtomicReference<String> message = new AtomicReference<>();
		final Runnable command = growCommand(false, "--time", "5", "key", Optional.of(address), Optional.of(number));

		final Result result = RestCommandFixture.run(() -> {
			try {
				command.run();
			} catch (CommandLine.ParameterException ex) {
				message.set(ex.getMessage());
			}
		}, Response.ok("{}").build());

		assertThat(address + " " + number, result.connection(), equalTo(Optional.empty()));
		assertThat(address + " " + number, message.get(), notNullValue());
		return message.get();
	}

	private static Result sent(String option, String address, String number) {
		return RestCommandFixture.run(growWith(option, "5", "key", Optional.of(address), Optional.of(number)),
			Response.ok("{}").build());
	}

	private static Request put(String address, String number) {
		return new Request(HttpMethod.PUT, LOCATION + "/" + address + "/" + number, Optional.of(Entity.text("5")),
			signedBy("key"));
	}

	private static String refusal(String address, String number) {
		final AtomicReference<String> message = new AtomicReference<>();
		final Runnable command = growWith("--height", "5", "key", Optional.of(address), Optional.of(number));

		final Result result = RestCommandFixture.run(() -> {
			try {
				command.run();
			} catch (CommandLine.ParameterException ex) {
				message.set(ex.getMessage());
			}
		}, Response.ok("{}").build());

		assertThat(address + " " + number, result.connection(), equalTo(Optional.empty()));
		assertThat(address + " " + number, message.get(), notNullValue());
		return message.get();
	}

	@Property(tries = 10)
	public void getsAndPrintsTheMintStorage(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 30)
	public void putsTheMintForTheAddressAndHeightSignedWithTheKey(@ForAll("keys") String key,
		@ForAll("amounts") String amount, @ForAll("addresses") String address, @ForAll @Positive int height,
		@ForAll("sporks") String spork, @ForAll Status status) {

		Assume.that(status != Status.UNAUTHORIZED);

		final Result result = RestCommandFixture.run(grow(amount, key, Optional.of(address),
			Optional.of(Integer.toString(height))), Response.status(status).entity(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, LOCATION + "/" + address + "/"
			+ height, Optional.of(Entity.text(amount)), signedBy(key)))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void sendsNothingWithoutAnAddress(@ForAll("amounts") String amount, @ForAll Optional<@Positive Integer> height) {
		final Result result = RestCommandFixture.run(grow(amount, "key", Optional.empty(), height.map(String::valueOf)),
			Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out().lines().toList(), equalTo(List.of(UNSPECIFIED)));
	}

	@Property(tries = 10)
	public void sendsNothingWithoutAHeightOrATime(@ForAll("amounts") String amount,
		@ForAll("addresses") String address) {

		final Result result = RestCommandFixture.run(grow(amount, "key", Optional.of(address), Optional.empty()),
			Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out().lines().toList(), equalTo(List.of(UNSPECIFIED)));
	}

	@Property(tries = 20)
	public void putsTheSecondsOfAnAccountThatAreGivenAsDigits(@ForAll("accounts") String account,
		@ForAll @LongRange(min = 0, max = UNSIGNED_MAXIMUM) long seconds) {

		for (String option : List.of("--height", "--time")) {
			assertThat(sent(option, account, Long.toString(seconds)).request(),
				equalTo(Optional.of(put(account, Long.toString(seconds)))));
		}
	}

	@Example
	public void putsTheSecondsOfAnAccountAtTheEdgesOfThirtyTwoBits() {
		for (String seconds : List.of("0", "1", "2147483647", "2147483648", "4000000000", "4294967295")) {
			assertThat(sent("--time", ACCOUNT, seconds).request(), equalTo(Optional.of(put(ACCOUNT, seconds))));
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
			assertThat(instant, sent("--time", ACCOUNT, instant).request(), equalTo(Optional.of(put(ACCOUNT, seconds))));
			assertThat(instant, sent("--height", ACCOUNT, instant).request(),
				equalTo(Optional.of(put(ACCOUNT, seconds))));
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

	@Property(tries = 20)
	public void refusesAnIsoInstantForAnAddressOfTheOldChain(@ForAll("oldAddresses") String address) {
		for (String instant : List.of("2026-06-01T00:00:00Z", "2026-06-01T02:00:00+02:00", "2026-06-01T00:00:00")) {
			final String message = refusal(address, instant);

			assertThat(message, containsString("old-style"));
			assertThat(message, containsString("height"));
			assertThat(message, containsString(instant));
		}
	}

	@Example
	public void takesOnlyAHeightForWhatIsNotALowerCaseAccount() {
		final String upperCase = ACCOUNT.toUpperCase();

		for (String address : List.of(upperCase, "garbage", "abc" + ACCOUNT, ACCOUNT.substring(1))) {
			assertThat(address, refusal(address, "2026-06-01T00:00:00Z"), containsString("height"));
			assertThat(address, sent("--height", address, "4200000").request(),
				equalTo(Optional.of(put(address, "4200000"))));
		}
	}

	@Property(tries = 20)
	public void putsTheHeightOfTheOldChainOfAnOldAddressAsDigits(@ForAll("oldAddresses") String address,
		@ForAll @IntRange(min = 0) int height) {

		assertThat(sent("--height", address, Integer.toString(height)).request(),
			equalTo(Optional.of(put(address, Integer.toString(height)))));
	}

	@Example
	public void refusesAHeightOfTheOldChainAbove2To31() {
		final String old = LegacyAddress.encode(new byte[HASH_LENGTH]);

		for (String height : List.of("2147483648", "4000000000", "4294967295")) {
			final String message = refusal(old, height);

			assertThat(message, containsString("old-style"));
			assertThat(message, containsString(height));
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
		assertThat(sentWithoutLeave(ACCOUNT, "2100-01-01T00:00:00Z").request(),
			equalTo(Optional.of(put(ACCOUNT, "4102444800"))));
		assertThat(sentWithoutLeave(ACCOUNT, "4102444800").request(), equalTo(Optional.of(put(ACCOUNT, "4102444800"))));
	}

	@Example
	public void takesATimeThatHasPassedWhenItIsAllowed() {
		assertThat(sent("--time", ACCOUNT, "20260601").request(), equalTo(Optional.of(put(ACCOUNT, "20260601"))));
		assertThat(sent("--time", ACCOUNT, "2026-06-01T00:00:00Z").request(),
			equalTo(Optional.of(put(ACCOUNT, "1780272000"))));
	}

	@Property(tries = 20)
	public void needsNoLeaveForAHeightOfTheOldChain(@ForAll("oldAddresses") String address,
		@ForAll @IntRange(min = 0) int height) {

		assertThat(sentWithoutLeave(address, Integer.toString(height)).request(),
			equalTo(Optional.of(put(address, Integer.toString(height)))));
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
	public void requiresAnAmountAndAKeyToGrow() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkGrow(), "-k", "key", NAME));

		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkGrow(), "-D", "1", NAME));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new MintStorage()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
