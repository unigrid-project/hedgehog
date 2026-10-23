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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.HedgehogCli;
import static org.unigrid.hedgehog.command.cli.SporkCommands.COSIGNER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposal;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkCosignTest {
	private static final int DIGEST_LENGTH = 128;

	@Provide
	public Arbitrary<List<String>> digests() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(DIGEST_LENGTH).list().ofMinSize(1)
			.ofMaxSize(3);
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	private static GridSporkCosign cosign(String key, List<String> digests) {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs(Stream.concat(Stream.of("-k", key), digests.stream()).toArray(String[]::new));
		return command;
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	private static MintStorage.SporkData mintsOf(Map<String, Integer> mints) {
		final MintStorage.SporkData data = new MintStorage.SporkData();

		data.setMints(new HashMap<>());
		mints.forEach((address, amount) -> data.getMints().put(new Location(new Address(address), 1),
			BigDecimal.valueOf(amount)));
		return data;
	}

	private static String oldAddress(int seed) {
		final byte[] hash = new byte[20];

		hash[0] = (byte) seed;
		return LegacyAddress.encode(hash);
	}

	@Property(tries = 5)
	public void showsWhatItCoSignsCoSignsItHereAndHandsItBack(@ForAll("digests") List<String> unknown) {
		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf(1_000_007));
		final GridSpork proposal = proposal(Type.MINT_SUPPLY, stored, supplyOf(2_000_003), PROPOSER);
		final String digest = PendingSporks.digestOf(proposal);
		final FakeSporkNode node = new FakeSporkNode().holding(stored).waitingOn(proposal);
		final Result result = RestCommandFixture.run(cosign(COSIGNER.getPrivateKey(), List.of(digest)), node);

		assertThat(result.requests().stream().map(Request::location).toList(), equalTo(List.of(
			FakeSporkNode.PENDING + "/" + digest, FakeSporkNode.WIRE + "/" + Type.MINT_SUPPLY, FakeSporkNode.WIRE)));
		assertThat(node.stored(Type.MINT_SUPPLY).isDoublySigned(), is(true));
		assertThat(node.stored(Type.MINT_SUPPLY).getSignable(), equalTo(proposal.getSignable()));
		assertThat(node.proposalOf(Type.MINT_SUPPLY).isPresent(), is(false));
		assertThat(result.out(), containsString("1000007"));
		assertThat(result.out(), containsString("2000003"));
		assertThat(result.out(), containsString(PROPOSER.getPublicKey()));
		assertThat(result.out().lines().toList().getLast(), equalTo("Co-signed " + digest));
		assertThat(result.err(), equalTo(""));
	}

	/* A proposal carries the data of the one it replaced, so the co-signer sees and signs every change since the
	   stored spork, not only the last one */
	@Example
	public void showsAChangeCarriedOverFromAnEarlierProposal() {
		final String kept = oldAddress(1);
		final String earlier = oldAddress(2);
		final String later = oldAddress(3);
		final GridSpork stored = stored(Type.MINT_STORAGE, mintsOf(Map.of(kept, 11)));
		final GridSpork first = proposal(Type.MINT_STORAGE, stored, mintsOf(Map.of(kept, 11, earlier, 22)), PROPOSER);
		final FakeSporkNode node = new FakeSporkNode().holding(stored).waitingOn(first);
		final GridSpork second = GridSpork.nextVersion(Type.MINT_STORAGE, stored, node.proposalOf(Type.MINT_STORAGE));

		second.<MintStorage.SporkData>getData().getMints().put(new Location(new Address(later), 1), BigDecimal.TEN);
		SporkCommands.sign(second, PROPOSER);
		node.waitingOn(second);

		final Result result = RestCommandFixture.run(cosign(COSIGNER.getPrivateKey(),
			List.of(PendingSporks.digestOf(second))), node);
		final String proposed = result.out().substring(result.out().indexOf("Proposed"));

		assertThat(result.out().substring(0, result.out().indexOf("Proposed")), not(containsString(earlier)));
		assertThat(proposed, containsString(earlier));
		assertThat(proposed, containsString(later));
		assertThat(proposed, containsString(kept));
		assertThat(node.stored(Type.MINT_STORAGE).<MintStorage.SporkData>getData().getMints().size(), equalTo(3));
	}

	@Example
	public void refusesToCoSignWithTheKeyThatProposed() {
		final GridSpork proposal = proposal(Type.MINT_SUPPLY, null, supplyOf(5), PROPOSER);
		final String digest = PendingSporks.digestOf(proposal);
		final FakeSporkNode node = new FakeSporkNode().waitingOn(proposal);
		final Result result = RestCommandFixture.run(cosign(PROPOSER.getPrivateKey(), List.of(digest)), node);

		assertThat(result.out().lines().toList().getLast(), containsString("Co-signing " + digest + " refused"));
		assertThat(node.submitted(), empty());
	}

	@Property(tries = 3)
	public void saysSoWhenNoSporkAwaitsUnderADigest(@ForAll("digests") List<String> digests) {
		final FakeSporkNode node = new FakeSporkNode();
		final Result result = RestCommandFixture.run(cosign(COSIGNER.getPrivateKey(), digests), node);

		assertThat(result.out().lines().toList(), equalTo(digests.stream()
			.map(digest -> "No spork awaits a co-signature under " + digest).toList()));
		assertThat(node.submitted(), empty());
	}

	@Example
	public void signsNothingTheNodeAnswersADigestWithThatIsAnotherSpork() {
		final GridSpork other = proposal(Type.MINT_SUPPLY, null, supplyOf(5), PROPOSER);
		final String digest = "ab".repeat(DIGEST_LENGTH / 2);
		final Result result = RestCommandFixture.run(cosign(COSIGNER.getPrivateKey(), List.of(digest)),
			request -> Response.ok(SporkWire.encode(other)).build());

		assertThat(result.out(), containsString("another spork"));
		assertThat(result.requests().stream().map(Request::method).toList(), equalTo(List.of(HttpMethod.GET)));
	}

	@Property(tries = 5)
	public void triesEveryDigestEvenAfterAnOddAnswer(@ForAll("digests") List<String> digests, @ForAll Status status) {
		final ResponseOddityException oddity = new ResponseOddityException(status);
		final Result result = RestCommandFixture.runWithOddity(cosign(COSIGNER.getPrivateKey(), digests), oddity);

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(Collections.nCopies(digests.size(), oddity.getMessage())));
	}

	@Example
	public void refusesWithoutABoardKeyBeforeAskingTheNode() {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs("ab");
		assertThat(SporkCommands.refusal(command), containsString("A board key is needed"));
		assertThat(SporkCommands.refusal(cosign(SporkCommands.STRANGER.getPrivateKey(), List.of("ab"))),
			containsString("not one of the current board keys"));
	}

	/* A key typed where a digest belongs would go to the node in a request and be printed back */
	@Example
	public void asksTheNodeForNothingButADigestAndRepeatsNothingElse() {
		final String key = SporkCommands.STRANGER.getPrivateKey();

		for (String notADigest : List.of(key, "ab", "AB".repeat(DIGEST_LENGTH / 2), "ab".repeat(DIGEST_LENGTH / 2) + "/x")) {
			final String refusal = SporkCommands.refusal(cosign(COSIGNER.getPrivateKey(),
				List.of("ab".repeat(DIGEST_LENGTH / 2), notADigest)));

			assertThat(refusal, containsString("digest"));
			assertThat(refusal, not(containsString(notADigest)));
		}
	}

	@Example
	public void endsWithAUsageErrorAndNoStackTraceWithoutAKey() {
		final HedgehogCli.Result result = HedgehogCli.run("cli", "gridspork-cosign", "ab");

		assertThat(result.exitCode(), equalTo(CommandLine.ExitCode.USAGE));
		assertThat(result.err(), containsString("A board key is needed"));
		result.assertNoStackTrace();
	}

	@Example
	public void requiresADigest() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> new CommandLine(new GridSporkCosign()).parseArgs("-k", "key"));
	}
}
