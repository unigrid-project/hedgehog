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

package org.unigrid.hedgehog.command.cli;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.STRANGER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposal;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkRenewTest {
	private static final long TYPES = Arrays.stream(Type.values()).filter(type -> type != Type.UNDEFINED).count();

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	private static GridSporkRenew renew(String key) {
		final GridSporkRenew command = new GridSporkRenew();

		new CommandLine(command).parseArgs("--key", key);
		return command;
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	private static List<String> methods(Result result) {
		return result.requests().stream().map(Request::method).toList();
	}

	@Example
	public void renewsEveryStoredSporkSignedHereAndSignsThemAllBeforeHandingAnyBack() {
		final GridSpork supply = stored(Type.MINT_SUPPLY, supplyOf(10));
		final GridSpork fee = stored(Type.LEDGER_FEE, new LedgerFee.SporkData());
		final FakeSporkNode node = new FakeSporkNode().holding(supply, fee);
		final Result result = RestCommandFixture.run(renew(PROPOSER.getPrivateKey()), node);

		assertThat(methods(result), equalTo(Stream.concat(Collections.nCopies((int) TYPES, HttpMethod.GET).stream(),
			Stream.of(HttpMethod.POST, HttpMethod.POST)).toList()));

		/* As the node holds them: an amount of the fee travels in units, and so comes back with eight decimals */
		for (GridSpork before : List.of(SporkWire.decode(SporkWire.encode(supply)),
			SporkWire.decode(SporkWire.encode(fee)))) {

			final GridSpork renewed = node.proposalOf(before.getType()).orElseThrow();

			assertThat(renewed.isPending(), is(true));
			assertThat(renewed.getData(), equalTo(before.getData()));
			assertThat(renewed.getPreviousData(), equalTo(before.getPreviousData()));
			assertThat(renewed.getTimeStamp(), greaterThan(before.getTimeStamp()));
			assertThat(NetworkKey.signerOf(renewed), equalTo(Optional.of(PROPOSER.getPublicKey())));
			assertThat(renewed.getSignatureLog().last().getSignature(), equalTo(before.getSignature()));
			assertThat(result.out(), containsString(PendingSporks.digestOf(renewed)));
		}

		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void saysSoWhenNothingIsStoredToRenew() {
		final Result result = RestCommandFixture.run(renew(PROPOSER.getPrivateKey()), new FakeSporkNode());

		assertThat(result.out().lines().toList(), equalTo(List.of("No sporks to renew")));
		assertThat(methods(result), equalTo(Collections.nCopies((int) TYPES, HttpMethod.GET)));
	}

	/* A version that no known key signed cannot enter the signature log, and then nothing is renewed at all */
	@Example
	public void proposesNothingWhenOneSporkCannotBeRenewed() {
		final GridSpork supply = stored(Type.MINT_SUPPLY, supplyOf(10));
		final GridSpork forged = proposal(Type.LEDGER_FEE, null, new LedgerFee.SporkData(), STRANGER);
		final FakeSporkNode node = new FakeSporkNode().holding(supply, forged);
		final Result result = RestCommandFixture.run(renew(PROPOSER.getPrivateKey()), node);

		assertThat(node.submitted(), empty());
		assertThat(result.err(), containsString("nothing was proposed"));
	}

	@Property(tries = 3)
	public void reportsAnOddAnswerOnce(@ForAll Status status) {
		final ResponseOddityException oddity = new ResponseOddityException(status);
		final Result result = RestCommandFixture.runWithOddity(renew(PROPOSER.getPrivateKey()), oddity);

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(oddity.getMessage())));
	}

	@Example
	public void refusesWithoutABoardKeyBeforeAskingTheNode() {
		final GridSporkRenew command = new GridSporkRenew();

		new CommandLine(command).parseArgs();
		assertThat(SporkCommands.refusal(command), containsString("A board key is needed"));
		assertThat(SporkCommands.refusal(renew(STRANGER.getPrivateKey())), containsString("not one of the current"));
	}
}
