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

package org.unigrid.hedgehog.command.cli.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.server.rest.entity.LedgerAccount;
import org.unigrid.hedgehog.server.rest.entity.LedgerBlock;
import org.unigrid.hedgehog.server.rest.entity.LedgerInfo;
import org.unigrid.hedgehog.server.rest.entity.LedgerTransactionView;
import picocli.CommandLine;

/* What the tests of the ledger commands have in common: the chain and the answers of a node that they are given, the
   run of a command against a node that is faked, and what to look at afterwards */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class LedgerCommandFixtures {
	static final String CHAIN_ID = "11".repeat(64);
	static final byte[] CHAIN = HexFormat.of().parseHex(CHAIN_ID);
	static final String OTHER_CHAIN_ID = "22".repeat(64);
	static final byte[] SEED = HexFormat.of().parseHex("42".repeat(32));
	static final String ID = "9f".repeat(64);
	static final String UNREADABLE = "The node answered something this command cannot read: ";
	static final ObjectMapper MAPPER = new ObjectMapper();

	static final LedgerInfo INFO = new LedgerInfo(true, false, "", true, "11".repeat(32), 3, "22".repeat(64),
		"12.50000000", 0L, List.of("33".repeat(32)), "0.00100000", 0, List.of(), List.of());

	static final LedgerAccount ACCOUNT = new LedgerAccount("ab".repeat(32), "account", "6.99900000", 1L,
		"10.00000000", null, null);

	/* What the command did against a node that was faked, and what it printed */
	record Outcome(List<Request> requests, boolean connected, boolean closed, HedgehogCli.Result cli) { }

	/* A command refused before it connected to anything, and what it printed */
	record Refusal(Result sent, HedgehogCli.Result cli) { }

	static LedgerBlock block(final List<LedgerTransactionView> transactions) {
		return new LedgerBlock(5, 0, 1_760_000_000_000L, "aa".repeat(64), "bb".repeat(64), "cc".repeat(64),
			"dd".repeat(64), "ee".repeat(32), transactions, List.of("11".repeat(32)), 1);
	}

	static String[] ledger(final String command, final String... arguments) {
		return Stream.concat(Stream.of("cli", "ledger", command), Stream.of(arguments)).toArray(String[]::new);
	}

	/* The paths of the command are those of the file system, so that no key file is a real one */
	static HedgehogCli.Result runOn(final FileSystem fs, final String command, final String... arguments) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath),
			ledger(command, arguments));
	}

	static Outcome runAgainst(final FakeLedgerNode node, final FileSystem fs, final String command,
		final String... arguments) {

		return outcomeOf(node, runOn(fs, command, arguments));
	}

	static Outcome runAgainst(final FakeLedgerNode node, final String command, final String... arguments) {
		return outcomeOf(node, HedgehogCli.run(ledger(command, arguments)));
	}

	private static Outcome outcomeOf(final FakeLedgerNode node, final HedgehogCli.Result cli) {
		return new Outcome(List.copyOf(node.requests()), node.connected(), node.closed(), cli);
	}

	static Refusal refusedOn(final FileSystem fs, final String command, final String... arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = RestCommandFixture.run(() -> cli.set(runOn(fs, command, arguments)),
			Response.ok().build());

		return new Refusal(sent, cli.get());
	}

	static Refusal refusedBeforeConnecting(final String command, final String... arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final Result sent = RestCommandFixture.run(() -> cli.set(HedgehogCli.run(ledger(command, arguments))),
			Response.ok().build());

		return new Refusal(sent, cli.get());
	}

	static Response noLedger() {
		final LedgerInfo info = new LedgerInfo(false, false, null, false, null, 0, null, null, null, null,
			null, 0, List.of(), List.of());

		return Response.ok(info).build();
	}

	static void assertNothingPosted(final Outcome outcome) {
		assertThat(outcome.requests().stream().anyMatch(request -> HttpMethod.POST.equals(request.method())),
			equalTo(false));
	}

	@SneakyThrows
	static String errorBody(final String text) {
		return MAPPER.writeValueAsString(Map.of("error", text));
	}
}
