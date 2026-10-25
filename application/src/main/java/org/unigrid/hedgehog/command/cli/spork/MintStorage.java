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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.Getter;
import org.apache.commons.lang3.ObjectUtils;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.bootstrap.LedgerText;
import org.unigrid.hedgehog.model.bootstrap.MintEntry;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

@Command(name = "mint-storage")
public class MintStorage implements Runnable {
	/* At most eighteen digits, so the number always fits a long before its range is judged */
	private static final Pattern DIGITS = Pattern.compile("[0-9]{1,18}");

	@Spec
	private CommandSpec spec;

	@Getter @CommandLine.Option(names = "--address", scope = CommandLine.ScopeType.INHERIT,
		description = "Address for the mint: an old-style Unigrid address, or a ledger account written as "
			+ "64 lower-case hex characters."
	)
	private String address;

	@Getter @CommandLine.Option(names = { "--height", "--time" }, scope = CommandLine.ScopeType.INHERIT,
		description = { "When the mint happens, in the unit of the address.",
			"Old-style address: the block height on the old chain, in digits.",
			"Ledger account: the time in epoch seconds, or as an ISO-8601 instant with a zone, like "
				+ "2026-06-01T00:00:00Z or 2026-06-01T02:00:00+02:00."
		}
	)
	private String number;

	@CommandLine.Option(names = "--allow-past", scope = CommandLine.ScopeType.INHERIT,
		description = "Allow a ledger account to be given a time that has already passed, which mints at once. "
			+ "Without it such a time is refused, as a mistake in the unit is easily made: 20260601 is seconds."
	)
	private boolean allowPast;

	@Override
	public void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			new RestClientCommand(HttpMethod.GET, "/gridspork/mint-storage") {
				@Override
				protected void execute(Response response) {
					System.out.println(Json.parse(response.readEntity(String.class)));
				}
			}.run();
		} else if (spec.parent().userObject() instanceof GridSporkGrow grow) {
			grow(grow);
		} else {
			/* Will only happen if we forget to extend this method */
			throw new UnsupportedOperationException();
		}
	}

	/* The mint is read before the key, as neither needs the node and a mistake in the mint is the likelier one */
	private void grow(GridSporkGrow grow) {
		if (ObjectUtils.anyNull(address, number)) {
			System.out.println("Both the address and its block height or time have to be specified");
			return;
		}

		final Location location = new Location(new Address(address), heightOfTheMint());
		final BigDecimal amount = amountOf(GridSporkGrow.getData());

		SporkProposal.propose(spec.commandLine(), GridSpork.Type.MINT_STORAGE,
			spork -> spork.<SporkData>getData().getMints().put(location, amount),
			grow.getBoardKey().resolve(NetworkKey::isTrusted), grow.isFirstVersion()
		);
	}

	private BigDecimal amountOf(String text) {
		try {
			return new BigDecimal(text.strip());
		} catch (NumberFormatException ex) {
			throw new ParameterException(spec.commandLine(), "The amount \"" + text + "\" is no number", ex);
		}
	}

	private int heightOfTheMint() {
		try {
			final long value = DIGITS.matcher(number).matches() ? Long.parseLong(number) : secondsOfTheInstant();
			final int stored = MintEntry.heightOf(address, value);

			requireNotPast(value);
			return stored;
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), ex.getMessage(), ex);
		}
	}

	/* Only a ledger account has a time, and a time that has passed mints at once: mostly a number meant for another
	   unit, like a date written in digits. A height of the old chain is no time and is not judged. */
	private void requireNotPast(final long seconds) {
		if (!allowPast && LedgerText.isAccount(address) && Instant.ofEpochSecond(seconds).isBefore(Instant.now())) {
			throw new IllegalArgumentException("The time '" + number + "' has already passed, so the mint would "
				+ "happen at once. Digits are seconds since 1970: " + seconds + " is "
				+ Instant.ofEpochSecond(seconds) + ". To give a date, write an ISO-8601 instant like "
				+ "2026-06-01T00:00:00Z; to mint at once, give --allow-past");
		}
	}

	/* Only a ledger account has a time to give, as an address of the old chain is placed by a height of that chain */
	private long secondsOfTheInstant() {
		if (!LedgerText.isAccount(address)) {
			throw new IllegalArgumentException("An old-style address takes a height of the old chain in digits, "
				+ "not '" + number + "'; only a ledger account of 64 lower-case hex characters takes a "
				+ "time");
		}

		try {
			return OffsetDateTime.parse(number).toInstant().getEpochSecond();
		} catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("'" + number + "' is neither digits nor an ISO-8601 instant with "
				+ "a zone, like 2026-06-01T00:00:00Z or 2026-06-01T02:00:00+02:00", ex);
		}
	}
}
