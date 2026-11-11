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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Answer;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class NodeAddTest {
	@Provide
	public Arbitrary<String> addresses() {
		final Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);

		return Combinators.combine(octet, octet, octet, octet, Arbitraries.integers().between(1, 65535))
			.as("%d.%d.%d.%d:%d"::formatted);
	}

	private static NodeAdd nodeAdd(String address) {
		final NodeAdd command = new NodeAdd();

		new CommandLine(command).parseArgs(address);
		return command;
	}

	private static URI locationOf(String address) {
		return URI.create("https://" + RestCommandFixture.HOST + "/node/" + address);
	}

	@Property(tries = 30)
	public void postsTheAddressAsTextAndPrintsWhereTheNodeWent(@ForAll("addresses") String address,
		@ForAll Status status) {

		Assume.that(status != Status.CONFLICT);

		final Result result = RestCommandFixture.run(nodeAdd(address), Response.status(status)
			.location(locationOf(address)).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.POST, "/node",
			Optional.of(Entity.text(address)), Optional.empty()))));

		assertThat(result.out().lines().toList(), equalTo(List.of(locationOf(address).toString())));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 10)
	public void reportsAConflictOnStandardError(@ForAll("addresses") String address) {
		final Result result = RestCommandFixture.run(nodeAdd(address), Response.status(Status.CONFLICT).build());

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(Status.CONFLICT.getReasonPhrase())));
	}

	/* As the shell sees it: through the command line of the application, which exits with what the command says */
	private static HedgehogCli.Result runThroughTheCli(String address, Answer node) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();

		RestCommandFixture.run(() -> cli.set(HedgehogCli.run("cli", "node-add", address)), node);
		return cli.get();
	}

	private static int exitCodeOf(String address, Answer node) {
		return runThroughTheCli(address, node).exitCode();
	}

	@Property(tries = 10)
	public void exitsWithFailedOnAConflictAndWithOkOtherwise(@ForAll("addresses") String address,
		@ForAll boolean conflict) {

		final Response answer = Response.status(conflict ? Status.CONFLICT : Status.CREATED)
			.location(locationOf(address)).build();

		assertThat(exitCodeOf(address, request -> answer), equalTo(conflict ? ExitCode.FAILED : ExitCode.OK));
	}

	/* The node answers 304 for its own address and for one it cannot use, which the client takes for no answer at all:
	   the line says that the address was not taken, and not only the status */
	@Property(tries = 10)
	public void saysThatTheNodeDidNotTakeTheAddress(@ForAll("addresses") String address) {
		final HedgehogCli.Result result = runThroughTheCli(address, request -> {
			throw new ResponseOddityException(Status.NOT_MODIFIED);
		});

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("The node did not take " + address
			+ ", which is its own address or one it cannot use")));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
	}

	@Property(tries = 5)
	public void exitsWithFailedWhenTheNodeCannotBeReached(@ForAll("addresses") String address) {
		assertThat(exitCodeOf(address, request -> {
			throw new ProcessingException("Connection refused");
		}), equalTo(ExitCode.FAILED));
	}

	@Example
	public void requiresAnAddress() {
		assertThrows(CommandLine.MissingParameterException.class, () -> new CommandLine(new NodeAdd()).parseArgs());
	}
}
