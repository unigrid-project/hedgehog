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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.cli.GridSporkCosign;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import picocli.CommandLine;

/* A change of the board keys as the board makes it online: one key of the new board proposes it, a second co-signs it.
   The release builds in keys 0 and 1, and the change adds key 2 and switches key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysSporkTest {
	private static final String NAME = "board-keys";
	private static final String FILE = "/change.json";
	private static final KeyChange CHANGE = change(rootOf(keys(0, 1)), List.of(), keys(1, 2), keys(0, 1, 2));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		SporkCommands.environmentKey(null);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static FileSystem holdingTheChange() {
		final FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
		final ProposedKeyChange proposed = new ProposedKeyChange(new ArrayList<>(List.of(publicKeysOf(keys(0, 1)))),
			CHANGE);

		Files.write(fs.getPath(FILE), new JsonConfiguration().getContext(ProposedKeyChange.class)
			.writeValueAsBytes(proposed));
		return fs;
	}

	private static CommandLine.IFactory readingFrom(FileSystem fs) {
		return new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				return type == BoardKeysSpork.class ? type.cast(new BoardKeysSpork(fs))
					: CommandLine.defaultFactory().create(type);
			}
		};
	}

	private static Runnable set(FileSystem fs, Signature key, String... more) {
		return (Runnable) new CommandLine(new GridSporkSet(), readingFrom(fs)).parseArgs(Stream.concat(Stream.of(
			"-D", FILE, "-k", key.getPrivateKey(), NAME), Stream.of(more)).toArray(String[]::new)).subcommand()
			.commandSpec().userObject();
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	@Example
	@SneakyThrows
	public void proposesTheChangeWithAKeyThatItMakesActiveForAKeyThatItAddsToCosign() {
		try (FileSystem fs = holdingTheChange()) {
			final FakeSporkNode node = new FakeSporkNode();
			final Result proposed = RestCommandFixture.run(set(fs, key(1), "--first-version"), node);
			final String digest = PendingSporks.digestOf(node.proposalOf(Type.BOARD_KEYS).orElseThrow());
			final GridSporkCosign cosign = new GridSporkCosign();

			assertThat(proposed.err(), equalTo(""));
			new CommandLine(cosign).parseArgs("-k", key(2).getPrivateKey(), "--first-version", digest);
			assertThat(RestCommandFixture.run(cosign, node).out(), containsString("Co-signed " + digest));
			assertThat(NetworkKey.getActiveKeys().active(), equalTo(Set.of(publicKeysOf(keys(1, 2)))));
		}
	}

	/* The key that the change adds proposes it, which the co-signer sees named */
	@Example
	@SneakyThrows
	public void showsTheCosignerTheKeyThatTheChangeAddsAsItsProposer() {
		try (FileSystem fs = holdingTheChange()) {
			final FakeSporkNode node = new FakeSporkNode();

			RestCommandFixture.run(set(fs, key(2), "--first-version"), node);

			final String digest = PendingSporks.digestOf(node.proposalOf(Type.BOARD_KEYS).orElseThrow());
			final GridSporkCosign cosign = new GridSporkCosign();

			new CommandLine(cosign).parseArgs("-k", key(1).getPrivateKey(), "--first-version", digest);
			assertThat(RestCommandFixture.run(cosign, node).out(), allOf(containsString("signed by "
				+ key(2).getPublicKey()), containsString("Co-signed " + digest)));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyThatTheChangeSwitchesOff() {
		try (FileSystem fs = holdingTheChange()) {
			assertThat(SporkCommands.refusalOnceAsked(set(fs, key(0), "--first-version"), new FakeSporkNode()),
				containsString("not one of the current board keys"));
		}
	}

	@Example
	@SneakyThrows
	public void makesTheFirstVersionOnlyWhenToldTo() {
		try (FileSystem fs = holdingTheChange()) {
			final FakeSporkNode node = new FakeSporkNode();

			assertThat(assertThrows(CommandLine.ParameterException.class, () -> RestCommandFixture.run(set(fs,
				key(1)), node)).getMessage(), containsString("--first-version"));
			assertThat(node.submitted(), empty());
		}
	}

	/* A spork that a key signed which the change switches off would count for nothing, so the board renews it first
	   with keys that stay, or says that it means to */
	@Example
	@SneakyThrows
	public void switchesOffNoKeyThatSignedAStoredSporkUnlessToldTo() {
		try (FileSystem fs = holdingTheChange()) {
			final FakeSporkNode node = new FakeSporkNode().holding(supply(key(0), key(1)));
			final Result refused = RestCommandFixture.run(set(fs, key(1), "--first-version"), node);

			assertThat(refused.err(), allOf(containsString(Type.MINT_SUPPLY.name()),
				containsString(key(0).getPublicKey().substring(0, 16)), containsString("--allow-uncounted")));
			assertThat(node.submitted(), empty());

			RestCommandFixture.run(set(fs, key(1), "--first-version", "--allow-uncounted"), node);
			assertThat(node.proposalOf(Type.BOARD_KEYS).isPresent(), is(true));
		}
	}

	@Example
	public void getsAndPrintsTheBoardKeysOfTheNode() {
		final Result result = RestCommandFixture.run(subcommand(new GridSporkGet(), NAME),
			Response.ok("{\"epoch\":1}").build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, "/gridspork/board-keys",
			Optional.empty(), Optional.empty()))));
		assertThat(SporkCommands.tree(result.out()), equalTo(SporkCommands.tree("{\"epoch\":1}")));
	}
}
