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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.command.cli.SporkCommands.signedBy;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkCosignTest {
	private static final int DIGEST_LENGTH = 64;

	@Provide
	public Arbitrary<String> keys() {
		return SporkCommands.keys();
	}

	@Provide
	public Arbitrary<List<String>> digests() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofLength(DIGEST_LENGTH).list().ofMinSize(1)
			.ofMaxSize(4);
	}

	/* Most answers end up refused, so the ones the command tells apart would otherwise rarely come up */
	@Provide
	public Arbitrary<Status> answers() {
		return Arbitraries.oneOf(Arbitraries.of(Status.OK, Status.NOT_FOUND, Status.UNAUTHORIZED),
			Arbitraries.of(Status.class));
	}

	private static GridSporkCosign cosign(String key, List<String> digests) {
		final GridSporkCosign command = new GridSporkCosign();

		new CommandLine(command).parseArgs(Stream.concat(Stream.of("-k", key), digests.stream()).toArray(String[]::new));
		return command;
	}

	private static String outcome(Status status, String digest, String reason) {
		return switch (status) {
			case OK -> "Co-signed " + digest;
			case NOT_FOUND -> "No spork awaits a co-signature under " + digest;
			case UNAUTHORIZED -> "Unauthorized";
			default -> "Co-signing " + digest + " refused: " + reason;
		};
	}

	@Property(tries = 30)
	public void putsAKeySignedCosignatureForEachDigestAndReportsEveryOutcome(@ForAll("keys") String key,
		@ForAll("digests") List<String> digests, @ForAll("answers") Status status, @ForAll @AlphaChars String reason) {

		final Result result = RestCommandFixture.run(cosign(key, digests), Response.status(status).entity(reason)
			.build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.PUT, "/gridspork/pending/"
			+ digests.getLast(), Optional.of(Entity.text("")), signedBy(key)))));

		assertThat(result.out().lines().toList(), equalTo(digests.stream().map(digest -> outcome(status, digest,
			reason)).toList()));

		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void triesEveryDigestEvenAfterAnOddAnswer(@ForAll("digests") List<String> digests, @ForAll Status status) {
		final ResponseOddityException oddity = new ResponseOddityException(status);
		final Result result = RestCommandFixture.runWithOddity(cosign("key", digests), oddity);

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(Collections.nCopies(digests.size(), oddity.getMessage())));
	}

	@Example
	public void requiresAKeyAndADigest() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> new CommandLine(new GridSporkCosign()).parseArgs("-k", "key"));

		assertThrows(CommandLine.MissingParameterException.class,
			() -> new CommandLine(new GridSporkCosign()).parseArgs("digest"));
	}
}
