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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.StringUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.hamcrest.Matcher;
import org.unigrid.hedgehog.command.cli.spork.GridSporkGrow;
import org.unigrid.hedgehog.command.cli.spork.GridSporkSet;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import picocli.CommandLine;

/* A stored spork that a key signed which the board switched off counts for nothing, and the node may hand out one that
   two stolen keys forged, so nothing is built on it and it is signed again only by name. The release builds in keys 0
   and 1; the first change adds key 2 and the second switches key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SwitchedOffSporkCommandTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final String ACCOUNT = "ab".repeat(32);

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		SporkCommands.environmentKey(null);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static <S extends GridSpork> S signedBy(S spork, Signature signer, Signature cosigner) {
		spork.archive();
		spork.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60));
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.signInOneForm(spork.getSignable()));
		return spork;
	}

	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		return signedBy(spork, signer, cosigner);
	}

	private static FakeSporkNode nodeHolding(GridSpork... sporks) {
		return new FakeSporkNode().holding(signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2))).holding(sporks);
	}

	private static <C> C parsed(C command, String... arguments) {
		new CommandLine(command).parseArgs(arguments);
		return command;
	}

	private static GridSporkRenew renew(String... switchedOff) {
		return parsed(new GridSporkRenew(), Stream.concat(Stream.of("-k", key(1).getPrivateKey()),
			Stream.of(switchedOff).flatMap(type -> Stream.of("--switched-off", type))).toArray(String[]::new));
	}

	private static GridSporkCosign cosign(String digest, String... switchedOff) {
		return parsed(new GridSporkCosign(), Stream.concat(Stream.of("-k", key(2).getPrivateKey(), digest),
			Stream.of(switchedOff).flatMap(type -> Stream.of("--switched-off", type))).toArray(String[]::new));
	}

	private static String prefixOf(Signature key) {
		return StringUtils.left(key.getPublicKey(), 16);
	}

	/* The type and the keys that signed it */
	private static Matcher<String> namesTheSwitchedOffSigners(Type type) {
		return allOf(containsString(type.name()), containsString(prefixOf(key(0)) + " (switched off)"),
			containsString(prefixOf(key(1))));
	}

	/* And the way to sign it again */
	private static Matcher<String> refusesTheSwitchedOffSigners(Type type) {
		return allOf(namesTheSwitchedOffSigners(type), containsString("--switched-off " + type));
	}

	@Example
	public void setsNothingOverASporkThatASwitchedOffKeySigned() {
		final FakeSporkNode node = nodeHolding(supply(key(0), key(1)));
		final Result result = RestCommandFixture.run(subcommand(new GridSporkSet(), "-D", "7", "-k",
			key(1).getPrivateKey(), "mint-supply"), node);

		assertThat(result.err(), refusesTheSwitchedOffSigners(Type.MINT_SUPPLY));
		assertThat(result.err(), containsString("gridspork-renew"));
		assertThat(node.submitted(), empty());
	}

	@Example
	public void growsNothingOverASporkThatASwitchedOffKeySigned() {
		final FakeSporkNode node = nodeHolding(signedBy(new MintStorage(), key(0), key(1)));
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGrow(), "-D", "1", "-k",
			key(1).getPrivateKey(), "mint-storage", "--address", ACCOUNT, "--time", "4102444800"), node);

		assertThat(result.err(), refusesTheSwitchedOffSigners(Type.MINT_STORAGE));
		assertThat(node.submitted(), empty());
	}

	@Example
	public void renewsNothingWhileASporkThatASwitchedOffKeySignedIsNotNamed() {
		final FakeSporkNode node = nodeHolding(supply(key(0), key(1)), signedBy(new LedgerFee(), key(1), key(2)));

		for (GridSporkRenew command : List.of(renew(), renew(Type.LEDGER_FEE.name()))) {
			final Result result = RestCommandFixture.run(command, node);

			assertThat(result.err(), refusesTheSwitchedOffSigners(Type.MINT_SUPPLY));
			assertThat(result.out(), not(containsString("Renewing")));
			assertThat(node.submitted(), empty());
		}
	}

	/* The second board member names the type as well, and sees the same */
	@Example
	public void renewsAndCosignsASporkThatASwitchedOffKeySignedOnceItIsNamed() {
		final MintSupply stored = supply(key(0), key(1));
		final FakeSporkNode node = nodeHolding(stored);
		final Result renewed = RestCommandFixture.run(renew(Type.MINT_SUPPLY.name()), node);
		final String digest = PendingSporks.digestOf(node.proposalOf(Type.MINT_SUPPLY).orElseThrow());

		assertThat(renewed.err(), equalTo(""));
		assertThat(renewed.out(), allOf(namesTheSwitchedOffSigners(Type.MINT_SUPPLY),
			containsString(stored.getTimeStamp().toString()), containsString("\"maxSupply\" : 10")));

		final Result unnamed = RestCommandFixture.run(cosign(digest), node);

		assertThat(unnamed.out(), allOf(containsString("Co-signing " + digest + " refused"),
			refusesTheSwitchedOffSigners(Type.MINT_SUPPLY)));
		assertThat(node.stored(Type.MINT_SUPPLY).isCounting(), is(false));

		final Result named = RestCommandFixture.run(cosign(digest, Type.MINT_SUPPLY.name()), node);

		assertThat(named.out(), allOf(containsString("Co-signed " + digest),
			namesTheSwitchedOffSigners(Type.MINT_SUPPLY)));
		assertThat(node.stored(Type.MINT_SUPPLY).isCounting(), is(true));
	}

	@Example
	public void renewsASporkThatActiveKeysSignedAsBefore() {
		final FakeSporkNode node = nodeHolding(supply(key(1), key(2)));
		final Result result = RestCommandFixture.run(renew(), node);

		assertThat(result.err(), equalTo(""));
		assertThat(result.out(), not(containsString("switched off")));
		assertThat(node.proposalOf(Type.MINT_SUPPLY).isPresent(), is(true));
	}
}
