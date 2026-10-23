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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.cli.spork.GridSporkSet;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.checkpointAt;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.withoutCheckpoint;
import picocli.CommandLine;

/* The commands that sign sporks go by the board keys of the node, once they hold from the root of this machine. The
   release builds in keys 0 and 1; the first change adds key 2 and the second switches key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysCommandLineTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		withoutCheckpoint();
		SporkCommands.environmentKey(null);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static FakeSporkNode nodeOfTheNewBoard() {
		return new FakeSporkNode().holding(signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2)));
	}

	private static Runnable setSupply(Signature key) {
		return subcommand(new GridSporkSet(), "-D", "7", "-k", key.getPrivateKey(), "--first-version",
			"mint-supply");
	}

	private static <C> C parsed(C command, String... arguments) {
		new CommandLine(command).parseArgs(arguments);
		return command;
	}

	private static GridSporkCosign cosign(Signature key, String digest) {
		return parsed(new GridSporkCosign(), "-k", key.getPrivateKey(), digest);
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	/* The usage error of a command that asked the node for its board keys and nothing else */
	private static String refusalOnceAsked(Runnable command, FakeSporkNode node) {
		final AtomicReference<String> message = new AtomicReference<>();
		final Result result = RestCommandFixture.run(() -> {
			try {
				command.run();
			} catch (CommandLine.ParameterException ex) {
				message.set(ex.getMessage());
			}
		}, node);

		assertThat(result.requests().stream().map(Request::location).toList(),
			contains(FakeSporkNode.WIRE + "/" + Type.BOARD_KEYS));
		assertThat(node.submitted(), empty());
		return String.valueOf(message.get());
	}

	@Example
	public void signsWithAKeyThatTheBoardKeysOfTheNodeAdded() {
		final FakeSporkNode node = nodeOfTheNewBoard();
		final Result result = RestCommandFixture.run(setSupply(key(2)), node);
		final Optional<GridSpork> proposal = node.proposalOf(Type.MINT_SUPPLY);

		assertThat(result.err(), equalTo(""));
		assertThat(result.requests().getFirst().location(), equalTo(FakeSporkNode.WIRE + "/" + Type.BOARD_KEYS));
		assertThat(proposal.flatMap(NetworkKey::signerOf), equalTo(Optional.of(key(2).getPublicKey())));
	}

	@Example
	public void refusesAKeyThatTheBoardKeysOfTheNodeSwitchedOff() {
		assertThat(refusalOnceAsked(setSupply(key(0)), nodeOfTheNewBoard()),
			containsString("not one of the current board keys"));
	}

	@Example
	public void signsNothingOverBoardKeysThatDoNotHoldFromTheRootOfThisMachine() {
		final byte[] elsewhere = rootOf(keys(0, 5));
		final FakeSporkNode node = new FakeSporkNode().holding(signedAnyway(chainOf(change(elsewhere, List.of(),
			keys(0, 1, 5), keys(0, 1, 5))), key(0), key(1)));
		final Result result = RestCommandFixture.run(setSupply(key(0)), node);

		assertThat(result.err(), containsString("board keys"));
		assertThat(node.submitted(), empty());
		assertThat(NetworkKey.getActiveKeys().length(), is(0));
	}

	/* A release that vouches for the first change takes no chain that goes another way from the root */
	@Example
	public void signsNothingOverBoardKeysThatDoNotHoldTheCheckpointOfTheRelease() {
		final KeyChange elsewhere = change(ROOT, List.of(), keys(0, 1, 5), keys(0, 1, 5));
		final FakeSporkNode node = new FakeSporkNode().holding(signedAnyway(chainOf(elsewhere), key(0), key(1)));

		checkpointAt(List.of(ADDING), true);

		final Result result = RestCommandFixture.run(setSupply(key(0)), node);

		assertThat(result.err(), containsString("board keys"));
		assertThat(node.submitted(), empty());
		assertThat(NetworkKey.getActiveKeys().length(), is(0));
	}

	@Example
	public void cosignsWithAKeyThatTheBoardKeysOfTheNodeAdded() {
		final FakeSporkNode node = nodeOfTheNewBoard();

		RestCommandFixture.run(setSupply(key(1)), node);

		final String digest = PendingSporks.digestOf(node.proposalOf(Type.MINT_SUPPLY).orElseThrow());
		final Result result = RestCommandFixture.run(cosign(key(2), digest), node);

		assertThat(result.out(), containsString("Co-signed " + digest));
		assertThat(node.stored(Type.MINT_SUPPLY).isCounting(), is(true));
	}

	@Example
	public void renewsWithAKeyThatTheBoardKeysOfTheNodeAdded() {
		final FakeSporkNode node = nodeOfTheNewBoard().holding(supply(key(1), key(2)));
		final Result renewed = RestCommandFixture.run(parsed(new GridSporkRenew(), "-k", key(2).getPrivateKey()),
			node);

		assertThat(renewed.err(), equalTo(""));
		assertThat(node.proposalOf(Type.MINT_SUPPLY).flatMap(NetworkKey::signerOf),
			equalTo(Optional.of(key(2).getPublicKey())));
	}
}
