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

package org.unigrid.hedgehog.command.util;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Connection;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class RestClientCommandTest {
	private static final List<String> METHODS = List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
		HttpMethod.DELETE);

	private static final Entity<String> BODY = Entity.text("body");
	private static final String HEADER = "X-Header";
	private static final Connection CONNECTION = new Connection(RestCommandFixture.HOST, RestCommandFixture.PORT,
		true, RestCommandFixture.TOKEN);

	private static RestClientCommand command(String method, String location, Optional<String> fallback) {
		return new RestClientCommand(method, location, fallback.<Supplier<?>>map(text -> () -> text)) {
			@Override
			@SuppressWarnings("unchecked")
			protected <T> Entity<T> getEntity() {
				return (Entity<T>) BODY;
			}

			@Override
			protected void execute(Response response) {
				System.out.println(executed(response.getStatus()));
			}
		};
	}

	private static String executed(int status) {
		return "Executed " + status;
	}

	private static boolean carriesAnEntity(String method) {
		return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method);
	}

	@Provide
	public Arbitrary<String> methods() {
		return Arbitraries.of(METHODS);
	}

	@Property(tries = 50)
	public void sendsTheRequestItDescribesOverASecureConnection(@ForAll("methods") String method,
		@ForAll @AlphaChars String path, @ForAll Optional<@AlphaChars String> header) {

		final RestClientCommand command = command(method, "/" + path, Optional.empty());
		final Optional<MultivaluedMap<String, Object>> headers = header.map(value -> new MultivaluedHashMap<>(
			Map.of(HEADER, value)));

		headers.ifPresent(command::setHeaders);

		final Result result = RestCommandFixture.run(command, Response.ok().build());
		final Optional<Entity<?>> entity = carriesAnEntity(method) ? Optional.of(BODY) : Optional.empty();
		final boolean postIgnoresHeaders = HttpMethod.POST.equals(method);

		assertThat(result.connection(), equalTo(Optional.of(CONNECTION)));
		assertThat(result.closed(), is(true));
		assertThat(result.request(), equalTo(Optional.of(new Request(method, "/" + path, entity,
			postIgnoresHeaders ? Optional.empty() : headers))));
	}

	@Property(tries = 50)
	public void handsEveryAnswerWithContentToTheCommand(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status, @ForAll Optional<@AlphaChars String> fallback) {

		Assume.that(!(HttpMethod.GET.equals(method) && status == Status.NO_CONTENT.getStatusCode()));
		Assume.that(!(HttpMethod.PUT.equals(method) && status == Status.UNAUTHORIZED.getStatusCode()));

		final Result result = RestCommandFixture.run(command(method, "/", fallback),
			Response.status(status).build());

		assertThat(result.out().lines().toList(), equalTo(List.of(executed(status))));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 20)
	public void printsTheFallbackOrStatusWhenNothingCameBack(@ForAll boolean put,
		@ForAll Optional<@AlphaChars String> fallback) {

		final Status status = put ? Status.UNAUTHORIZED : Status.NO_CONTENT;
		final RestClientCommand command = command(put ? HttpMethod.PUT : HttpMethod.GET, "/", fallback);
		final Result result = RestCommandFixture.run(command, Response.status(status).build());

		assertThat(result.out().lines().toList(), equalTo(List.of(fallback.orElse(status.getReasonPhrase()))));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 20)
	public void refusesAnUnsupportedMethod(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String method) {
		Assume.that(!METHODS.contains(method));
		assertThrows(UnsupportedOperationException.class, () -> RestCommandFixture.run(command(method, "/",
			Optional.empty()), Response.ok().build()));
	}

	@Property(tries = 20)
	public void cannotSendOrHandleAnythingUntilTaughtTo(@ForAll("methods") String method) {
		assertThrows(UnsupportedOperationException.class, () -> RestCommandFixture.run(new RestClientCommand(method,
			"/"), Response.ok().build()));
	}

	@Property(tries = 50)
	public void reportsAnOddAnswerOnStandardError(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status) {

		final ResponseOddityException oddity = new ResponseOddityException(Response.status(status).build()
			.getStatusInfo());

		final Result result = RestCommandFixture.runWithOddity(command(method, "/", Optional.empty()), oddity);

		assertThat(result.closed(), is(true));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(oddity.getMessage())));
	}

	@Property(tries = 20)
	public void pointsAtTheMissingTokenFile(@ForAll("methods") String method, @ForAll @AlphaChars String file) {
		final Result result = RestCommandFixture.runWithoutToken(command(method, "/", Optional.empty()),
			new NoSuchFileException("/" + file));

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("No REST token in /" + file
			+ "; is the daemon running? Otherwise pass --resttoken.")));
	}

	@Property(tries = 20)
	public void reportsATokenThatCannotBeRead(@ForAll("methods") String method, @ForAll @AlphaChars String problem) {

		final Result result = RestCommandFixture.runWithoutToken(command(method, "/", Optional.empty()),
			new IOException(problem));

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(problem)));
	}
}
