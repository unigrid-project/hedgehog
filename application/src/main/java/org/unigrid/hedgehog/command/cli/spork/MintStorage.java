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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ObjectUtils;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.model.Json;
import org.unigrid.hedgehog.model.bootstrap.MintEntry;
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

	@Override
	@SneakyThrows
	public void run() {
		if (spec.parent().userObject() instanceof GridSporkGet) {
			final RestClientCommand cmd = new RestClientCommand(HttpMethod.GET, "/gridspork/mint-storage") {
				@Override
				protected void execute(Response response) {
					System.out.println(Json.parse(response.readEntity(String.class)));
				}
			};

			cmd.run();
		} else if (spec.parent().userObject() instanceof GridSporkGrow) {
			if (ObjectUtils.anyNull(address, number)) {
				System.out.println("Both the address and its block height or time have to be specified");
			} else {
				final String url = "/gridspork/mint-storage/%s/%s".formatted(address, numberOfTheMint());
				final RestClientCommand cmd = new RestClientCommand(HttpMethod.PUT, url) {
					@Override
					protected <T> Entity<T> getEntity() {
						return (Entity<T>) Entity.text(GridSporkGrow.getData());
					}

					@Override
					protected void execute(Response response) {
						System.out.println(Json.parse(response.readEntity(String.class)));
					}
				};

				final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
				headers.add("privateKey", GridSporkGrow.getKey());

				cmd.setHeaders(headers);
				cmd.run();
			}
		} else {
			/* Will only happen if we forget to extend this method */
			throw new UnsupportedOperationException();
		}
	}

	private String numberOfTheMint() {
		try {
			final long value = DIGITS.matcher(number).matches() ? Long.parseLong(number) : secondsOfTheInstant();

			return Integer.toUnsignedString(MintEntry.heightOf(address, value));
		} catch (IllegalArgumentException ex) {
			throw new ParameterException(spec.commandLine(), ex.getMessage(), ex);
		}
	}

	/* Only a ledger account has a time to give, as an address of the old chain is placed by a height of that chain */
	private long secondsOfTheInstant() {
		if (!MintEntry.isAccount(address)) {
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
