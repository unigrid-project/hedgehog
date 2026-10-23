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
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.STRANGER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposal;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposed;
import static org.unigrid.hedgehog.command.cli.SporkCommands.refusal;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply.SporkData;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class MintSupplyTest {
	private static final String NAME = "mint-supply";
	private static final String LOCATION = "/gridspork/mint-supply";

	@Provide
	public Arbitrary<String> supplies() {
		return SporkCommands.amounts();
	}

	@Provide
	public Arbitrary<String> sporks() {
		return SporkCommands.reports();
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	private static SporkData supplyOf(String maxSupply) {
		final SporkData data = new SporkData();

		data.setMaxSupply(new BigDecimal(maxSupply));
		return data;
	}

	private static Runnable set(String supply, String key, boolean optionsLast) {
		return optionsLast ? subcommand(new GridSporkSet(), NAME, "-D", supply, "-k", key)
			: subcommand(new GridSporkSet(), "--data", supply, "--key", key, NAME);
	}

	@Property(tries = 10)
	public void getsAndPrintsTheMintSupply(@ForAll("sporks") String spork) {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME), Response.ok(spork).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, LOCATION, Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(spork)));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void proposesTheSupplyOverTheStoredSporkSignedHereWithTheKey(@ForAll("supplies") String supply,
		@ForAll boolean optionsLast) {

		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf("10"));
		final FakeSporkNode node = new FakeSporkNode().holding(stored);
		final Result result = RestCommandFixture.run(set(supply, PROPOSER.getPrivateKey(), optionsLast), node);
		final GridSpork proposal = proposed(result, node, Type.MINT_SUPPLY);

		assertThat(proposal.<SporkData>getData().getMaxSupply(), equalTo(new BigDecimal(supply)));
		assertThat(proposal.getPreviousData(), equalTo(stored.getData()));
		assertThat(proposal.getPreviousTimeStamp(), equalTo(stored.getTimeStamp()));
		assertThat(node.stored(Type.MINT_SUPPLY), equalTo(stored));
	}

	/* The new supply takes the place of the one a proposal waiting for its co-signature would set */
	@Example
	public void proposesOverTheProposalThatAwaitsItsCosignature() {
		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf("10"));
		final FakeSporkNode node = new FakeSporkNode().holding(stored)
			.waitingOn(proposal(Type.MINT_SUPPLY, stored, supplyOf("20"), PROPOSER));
		final Result result = RestCommandFixture.run(set("30", PROPOSER.getPrivateKey(), true), node);

		assertThat(proposed(result, node, Type.MINT_SUPPLY).<SporkData>getData().getMaxSupply(),
			equalTo(new BigDecimal("30")));
	}

	@Example
	public void refusesASupplyThatNoNodeCanHoldBeforeSigningIt() {
		for (String supply : List.of("-1", "0.123456789", "1E+13", "ten")) {
			final FakeSporkNode node = new FakeSporkNode();

			assertThat(supply, assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(
				set(supply, PROPOSER.getPrivateKey(), false), node)).getMessage(), containsString("supply"));
			assertThat(supply, node.submitted(), empty());
		}
	}

	@Example
	public void takesTheKeyFromTheEnvironment() {
		final FakeSporkNode node = new FakeSporkNode();

		SporkCommands.environmentKey(PROPOSER.getPrivateKey());
		proposed(RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", "7", NAME), node), node,
			Type.MINT_SUPPLY);

		assertThat(refusal(set("7", PROPOSER.getPrivateKey(), true)), containsString("-k and $HEDGEHOG_BOARD_KEY"));
	}

	@Example
	public void refusesWithoutAKeyOfTheBoardBeforeAskingTheNode() {
		assertThat(refusal(set("1", STRANGER.getPrivateKey(), false)), containsString("not one of the current"));
		assertThat(refusal(subcommand(new GridSporkSet(), "-D", "1", NAME)), containsString("A board key is needed"));
	}

	@Example
	public void requiresASupply() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> subcommand(new GridSporkSet(), "-k", "key", NAME));
	}

	@Example
	public void refusesToRunUnderAnUnknownCommand() {
		final CommandSpec unknown = CommandSpec.create().addSubcommand(NAME, new CommandLine(new MintSupply()));

		assertThrows(UnsupportedOperationException.class, () -> subcommand(unknown, NAME).run());
	}
}
