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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.command.cli.SporkCommands.COSIGNER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.PROPOSER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.RETIRED;
import static org.unigrid.hedgehog.command.cli.SporkCommands.STRANGER;
import static org.unigrid.hedgehog.command.cli.SporkCommands.proposal;
import static org.unigrid.hedgehog.command.cli.SporkCommands.stored;
import static org.unigrid.hedgehog.command.cli.SporkCommands.subcommand;
import org.unigrid.hedgehog.command.cli.spork.GridSporkSet;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import picocli.CommandLine;

/* The client trusts any certificate and the node may not be what it claims, so whatever it hands out is checked
   before a board member signs on top of it: a stored spork has to be one that the network took, and a proposal one
   that a current board key made from that spork. Otherwise nothing is signed and nothing is sent. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ForgedSporkTest {
	private static final String UNTRUSTED_STORED = "is not one that board keys signed";
	private static final String UNTRUSTED_PROPOSAL = "is not one that a current board key made";

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.fakeTheBoard();
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	private static Runnable set(long maxSupply) {
		return subcommand(new GridSporkSet(), "-D", Long.toString(maxSupply), "-k", PROPOSER.getPrivateKey(),
			"mint-supply");
	}

	private static GridSporkCosign cosign(String digest) {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs("-k", COSIGNER.getPrivateKey(), digest);
		return command;
	}

	private static GridSporkRenew renew() {
		final GridSporkRenew command = new GridSporkRenew();

		new CommandLine(command).parseArgs("-k", PROPOSER.getPrivateKey());
		return command;
	}

	private static List<String> methodsOf(Result result) {
		return result.requests().stream().map(Request::method).toList();
	}

	/* Signed once with a key of the board, co-signed by no board key or by the same one, or changed once signed */
	private static Map<String, GridSpork> forgedStoredSporks() {
		final GridSpork once = proposal(Type.MINT_SUPPLY, null, supplyOf(10), PROPOSER);
		final GridSpork byAStranger = proposal(Type.MINT_SUPPLY, null, supplyOf(10), STRANGER);
		final GridSpork selfCosigned = SerializationUtils.clone(once);
		final GridSpork changed = stored(Type.MINT_SUPPLY, supplyOf(10));

		selfCosigned.setCosignature(once.getSignature());
		byAStranger.setCosignature(SporkCommands.signatureOf(COSIGNER, byAStranger));
		changed.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(1_000_000));
		return Map.of("signed once", once, "co-signed by itself", selfCosigned, "signed by a stranger", byAStranger,
			"changed once signed", changed);
	}

	@Example
	public void signsNothingOverAStoredSporkThatTheNetworkDidNotTake() {
		forgedStoredSporks().forEach((how, forged) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(forged);
			final Result result = RestCommandFixture.run(set(20), node);

			assertThat(how, result.err(), containsString(UNTRUSTED_STORED));
			assertThat(how, methodsOf(result), not(hasItem(HttpMethod.POST)));
			assertThat(how, node.submitted(), empty());
		});
	}

	@Example
	public void renewsNothingWhenOneStoredSporkIsForged() {
		forgedStoredSporks().forEach((how, forged) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(stored(Type.LEDGER_FEE, new LedgerFee.SporkData()),
				forged);
			final Result result = RestCommandFixture.run(renew(), node);

			assertThat(how, result.err(), containsString(UNTRUSTED_STORED));
			assertThat(how, result.err(), containsString("nothing was proposed"));
			assertThat(how, node.submitted(), empty());
		});
	}

	/* Made by no board key, changed once signed, or made from another version than the stored one: a sibling of it
	   that every node would take over it, so the co-signer would sign a change they were never shown */
	private static Map<String, GridSpork> forgedProposalsOver(GridSpork stored) {
		final GridSpork changed = proposal(Type.MINT_SUPPLY, stored, supplyOf(20), PROPOSER);
		final GridSpork sibling = stored(Type.MINT_SUPPLY, supplyOf(99));

		changed.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(21));
		return Map.of("made by a stranger", proposal(Type.MINT_SUPPLY, stored, supplyOf(20), STRANGER),
			"changed once signed", changed,
			"made from another version", proposal(Type.MINT_SUPPLY, sibling, supplyOf(20), PROPOSER));
	}

	@Example
	public void signsNothingOverAProposalThatNoCurrentBoardKeyMadeFromTheStoredSpork() {
		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf(10));

		forgedProposalsOver(stored).forEach((how, forged) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(stored).handingOut(forged);
			final Result result = RestCommandFixture.run(set(30), node);

			assertThat(how, result.err(), containsString(UNTRUSTED_PROPOSAL));
			assertThat(how, node.submitted(), empty());
		});
	}

	@Example
	public void coSignsNoProposalThatNoCurrentBoardKeyMadeFromTheStoredSpork() {
		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf(10));

		forgedProposalsOver(stored).forEach((how, forged) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(stored).handingOut(forged);
			final Result result = RestCommandFixture.run(cosign(PendingSporks.digestOf(forged)), node);

			assertThat(how, result.out(), containsString(UNTRUSTED_PROPOSAL));
			assertThat(how, result.out(), not(containsString("Proposed data")));
			assertThat(how, node.submitted(), empty());
		});
	}

	private static String shortly(String hex) {
		return hex.substring(0, 16);
	}

	/* What a build from before co-signatures stored: signed once, over its Java serialization */
	private static GridSpork signedOnceByAnEarlierBuild(Signature signer) {
		final GridSpork spork = GridSpork.create(Type.MINT_SUPPLY);

		spork.setData(supplyOf(10));
		spork.setPreviousData(supplyOf(0));
		spork.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60));
		spork.setPreviousTimeStamp(Instant.EPOCH);
		spork.setSignature(SporkCommands.signatureOf(signer, spork));
		return spork;
	}

	/* A node may hand out a head that passes for one the network took and is not the one it holds, which only a
	   second node tells, so the head is shown short enough to hold against it before anything is signed over it */
	@Example
	public void showsTheStoredHeadBeforeSigningOverIt() {
		final GridSpork stored = stored(Type.MINT_SUPPLY, supplyOf(10));
		final FakeSporkNode node = new FakeSporkNode().holding(stored);
		final Result result = RestCommandFixture.run(set(20), node);

		assertThat(result.out(), containsString("made at " + stored.getTimeStamp()));
		assertThat(result.out(), containsString("digest " + shortly(PendingSporks.digestOf(stored))));
		assertThat(result.out(), containsString("signed by " + shortly(PROPOSER.getPublicKey()) + " and "
			+ shortly(COSIGNER.getPublicKey())));
		assertThat(result.out(), not(containsString("retired")));
		assertThat(node.submitted(), hasSize(1));
	}

	/* A retired key and the single signature of a build from before co-signatures are what a forged head needs */
	@Example
	public void marksAHeadSignedOnceByARetiredKeyWhereverItIsSignedOver() {
		final GridSpork stored = signedOnceByAnEarlierBuild(RETIRED);
		final String head = "signed once by " + shortly(RETIRED.getPublicKey()) + " (retired key), in the form "
			+ "of a build from before co-signatures";
		final GridSpork proposal = proposal(Type.MINT_SUPPLY, stored, supplyOf(20), PROPOSER);

		assertThat(RestCommandFixture.run(set(20), new FakeSporkNode().holding(stored)).out(),
			containsString(head));
		assertThat(RestCommandFixture.run(renew(), new FakeSporkNode().holding(stored)).out(), containsString(head));
		assertThat(RestCommandFixture.run(cosign(PendingSporks.digestOf(proposal)),
			new FakeSporkNode().holding(stored).waitingOn(proposal)).out(), containsString(head));
	}

	@Example
	public void coSignsNothingOverAStoredSporkThatTheNetworkDidNotTake() {
		final GridSpork over = proposal(Type.MINT_SUPPLY, null, supplyOf(20), PROPOSER);

		forgedStoredSporks().forEach((how, forged) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(forged).handingOut(over);
			final Result result = RestCommandFixture.run(cosign(PendingSporks.digestOf(over)), node);

			assertThat(how, result.out(), containsString(UNTRUSTED_STORED));
			assertThat(how, node.submitted(), empty());
		});
	}

	/* The next version over a stored spork with the time stamp given, signed by the keys given */
	private static GridSpork signedAt(Instant stamp, GridSpork stored, long maxSupply, Signature... signers) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		spork.setData(supplyOf(maxSupply));
		spork.setTimeStamp(stamp);
		SporkCommands.sign(spork, signers[0]);

		if (signers.length > 1) {
			spork.setCosignature(SporkCommands.signatureOf(signers[1], spork));
		}

		return spork;
	}

	/* An honest node drops a proposal once it is older than a proposal waits and takes none stamped further ahead
	   than clocks drift apart, so one outside that span is replayed or forged */
	@Example
	public void coSignsNoProposalOlderThanAProposalWaitsOrAheadOfTheClock() {
		final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		final GridSpork stored = signedAt(now.minus(PendingSporks.LIFETIME).minusSeconds(3600), null, 10, PROPOSER,
			COSIGNER);
		final Map<String, GridSpork> outOfTime = Map.of(
			"longer ago than a proposal waits",
			signedAt(now.minus(PendingSporks.LIFETIME).minusSeconds(60), stored, 20, PROPOSER),
			"stamped ahead of the clock of this machine",
			signedAt(now.plus(PendingSporks.LEEWAY).plusSeconds(60), stored, 20, PROPOSER));

		outOfTime.forEach((how, proposal) -> {
			final FakeSporkNode node = new FakeSporkNode().holding(stored).handingOut(proposal);
			final Result cosigning = RestCommandFixture.run(cosign(PendingSporks.digestOf(proposal)), node);
			final Result setting = RestCommandFixture.run(set(30), node);

			assertThat(how, cosigning.out(), containsString("refused: The MINT_SUPPLY proposal"));
			assertThat(how, cosigning.out(), containsString(how));
			assertThat(how, setting.err(), containsString(how));
			assertThat(how, node.submitted(), empty());
		});
	}

	private static boolean isPrintable(String text) {
		return text.codePoints().allMatch(point -> point == '\n' || !Character.isISOControl(point));
	}

	/* A terminal acts on control characters, which a node can put in what it answers and in the data it holds */
	@Example
	public void showsNoControlCharacterThatTheNodeSends() {
		final String hostile = "\u001b]0;taken\u0007\u001b[2J\u009b31m\rrefused";
		final MintStorage.SporkData mints = new MintStorage().getData();

		mints.getMints().put(new Location(new Address("x\u009b2J\u001b[8m"), 1), BigDecimal.ONE);

		final GridSpork stored = stored(Type.MINT_STORAGE, mints);
		final GridSpork proposal = proposal(Type.MINT_STORAGE, stored, mints, PROPOSER);
		final FakeSporkNode node = new FakeSporkNode().holding(stored, stored(Type.MINT_SUPPLY, supplyOf(10)))
			.waitingOn(proposal);
		final RestCommandFixture.Answer refusing = request -> HttpMethod.POST.equals(request.method())
			? Response.status(Status.CONFLICT).entity(hostile).build() : node.respond(request);

		for (Result result : List.of(RestCommandFixture.run(set(30), refusing),
			RestCommandFixture.run(cosign(PendingSporks.digestOf(proposal)), refusing))) {

			assertThat(result.out(), containsString("refused"));
			assertThat(result.out(), isPrintable(result.out()), is(true));
		}

		final String shown = RestCommandFixture.run(cosign(PendingSporks.digestOf(proposal)), node).out();

		assertThat(shown, containsString("x2J"));
		assertThat(shown, isPrintable(shown), is(true));
	}

	@Example
	public void signsNothingOverAStoredSporkStampedAheadOfTheClock() {
		final Instant ahead = Instant.now().plus(PendingSporks.LEEWAY).plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
		final GridSpork stored = signedAt(ahead, null, 10, PROPOSER, COSIGNER);
		final FakeSporkNode node = new FakeSporkNode().holding(stored);

		assertThat(RestCommandFixture.run(set(20), node).err(), containsString("stamped ahead of the clock"));
		assertThat(RestCommandFixture.run(renew(), node).err(), containsString("stamped ahead of the clock"));
		assertThat(node.submitted(), empty());
	}
}
