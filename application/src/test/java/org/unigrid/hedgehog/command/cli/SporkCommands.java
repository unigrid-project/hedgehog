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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.util.FakeSporkNode;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import picocli.CommandLine;

/* The arbitraries are plain factories for each test to provide, since jqwik creates a supplier reflectively and a
   test run only opens the packages of the tests it selects. The board keys are made once for the run, as a P-521 key
   pair takes a dozen tries to generate. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkCommands {
	public static final Signature PROPOSER = keyPair();
	public static final Signature COSIGNER = keyPair();
	public static final Signature STRANGER = keyPair();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	/* The proposer and the co-signer are the board, and the environment gives no key */
	public static void fakeTheBoard() {
		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { PROPOSER.getPublicKey(), COSIGNER.getPublicKey() };
			}

			@Mock public String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};

		environmentKey(null);
	}

	public static void environmentKey(String key) {
		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return key;
			}
		};
	}

	@SneakyThrows
	public static JsonNode tree(String json) {
		return MAPPER.readTree(json);
	}

	@SneakyThrows
	public static String json(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	public static Runnable subcommand(Object parent, String... arguments) {
		return (Runnable) new CommandLine(parent).parseArgs(arguments).subcommand().commandSpec().userObject();
	}

	public static Arbitrary<String> amounts() {
		return Arbitraries.bigDecimals().between(new BigDecimal("0.00000001"), BigDecimal.valueOf(1_000_000))
			.ofScale(8).map(BigDecimal::toPlainString);
	}

	public static Arbitrary<String> reports() {
		return Arbitraries.maps(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12), Arbitraries.integers())
			.ofMaxSize(5).map(SporkCommands::json);
	}

	/* A spork of this data as the board stores it: the version over nothing, signed and co-signed */
	@SneakyThrows
	public static GridSpork stored(GridSpork.Type type, ChunkData data) {
		final GridSpork spork = proposal(type, null, data, PROPOSER);

		spork.cosign(COSIGNER.getPrivateKey());
		return spork;
	}

	/* The next version over a stored spork, with this data and signed once */
	public static GridSpork proposal(GridSpork.Type type, GridSpork stored, ChunkData data, Signature signer) {
		final GridSpork spork = GridSpork.nextVersion(type, stored, Optional.empty());

		spork.setData(data);
		sign(spork, signer);
		return spork;
	}

	@SneakyThrows
	public static void sign(GridSpork spork, Signature signer) {
		spork.sign(signer.getPrivateKey());
	}

	/* A signature of the spork as it stands, whoever the key belongs to */
	@SneakyThrows
	public static byte[] signatureOf(Signature signer, GridSpork spork) {
		return signer.sign(spork.getSignable());
	}

	/* The spork that a command handed the node last, in the form it went */
	public static GridSpork submitted(Result result) {
		final Request request = result.request().orElseThrow();

		assertThat(request.method(), equalTo(HttpMethod.POST));
		assertThat(request.location(), equalTo(FakeSporkNode.WIRE));
		return SporkWire.decode((byte[]) request.entity().orElseThrow().getEntity());
	}

	/* What a command handed the node as a proposal, signed here with the proposer's key and now held by the node */
	public static GridSpork proposed(Result result, FakeSporkNode node, GridSpork.Type type) {
		final GridSpork proposal = submitted(result);
		final String digest = PendingSporks.digestOf(proposal);

		assertThat(result.err(), equalTo(""));
		assertThat(result.requests().getFirst().location(), equalTo(FakeSporkNode.WIRE + "/" + type));
		assertThat(proposal.getType(), equalTo(type));
		assertThat(proposal.isPending(), is(true));
		assertThat(NetworkKey.signerOf(proposal), equalTo(Optional.of(PROPOSER.getPublicKey())));
		assertThat(node.proposalOf(type).map(PendingSporks::digestOf), equalTo(Optional.of(digest)));
		assertThat(result.out(), containsString(Status.ACCEPTED.getReasonPhrase()));
		assertThat(result.out(), containsString(digest));
		return proposal;
	}

	/* The usage error of a command that refused before it asked the node anything */
	public static String refusal(Runnable command) {
		final AtomicReference<String> message = new AtomicReference<>();
		final Result result = RestCommandFixture.run(() -> {
			try {
				command.run();
			} catch (CommandLine.ParameterException ex) {
				message.set(ex.getMessage());
			}
		}, Response.ok().build());

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(message.get(), notNullValue());
		return message.get();
	}
}
