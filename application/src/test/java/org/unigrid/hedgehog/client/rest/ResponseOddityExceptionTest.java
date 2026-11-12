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

package org.unigrid.hedgehog.client.rest;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.jqwik.MockitHook;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ResponseOddityExceptionTest {
	private static final int KEPT = 8 * 1024;

	private static ResponseOddityException oddityOf(final Response canned) {
		final AtomicReference<ResponseOddityException> oddity = new AtomicReference<>();

		RestCommandFixture.run(() -> oddity.set(ResponseOddityException.of(canned)), canned);
		return oddity.get();
	}

	@Example
	public void shouldKeepTheBodyOfARefusalWhole() {
		final ResponseOddityException oddity = oddityOf(Response.status(Status.CONFLICT)
			.entity("{\"error\":\"Balance does not cover the transfer\"}").build());

		assertThat(oddity.getMessage(), startsWith("409 "));
		assertThat(oddity.getBody(), equalTo("{\"error\":\"Balance does not cover the transfer\"}"));
	}

	@Example
	public void shouldKeepNoMoreThanTheFirstKilobytesOfALargeBody() {
		final String large = "x".repeat(1 << 20);
		final ResponseOddityException oddity = oddityOf(Response.status(Status.BAD_GATEWAY).entity(large).build());

		assertThat(oddity.getMessage(), startsWith("502 "));
		assertThat(oddity.getBody(), equalTo(large.substring(0, KEPT)));
	}

	@Example
	public void shouldCutTheBodyByBytesAndReadItAsUtf8() {
		final String umlauts = "ö".repeat(KEPT);

		assertThat(oddityOf(Response.status(Status.BAD_GATEWAY).entity(umlauts).build()).getBody(),
			equalTo("ö".repeat(KEPT / 2)));
	}

	/* A node, or a proxy in front of it, may answer with a status that jakarta has no name for */
	@Example
	public void shouldNameAStatusThatHasNoNameByItsNumber() {
		final ResponseOddityException oddity = new ResponseOddityException(Response.status(599, "Odd").build()
			.getStatusInfo());

		assertThat(oddity.getMessage(), equalTo("599 (Odd)"));
		assertThat(oddity.getStatusCode(), equalTo(599));
	}

	@Example
	public void shouldNotShowAnEmptyReasonOfAStatusThatHasNoName() {
		assertThat(new ResponseOddityException(Response.status(599, "").build().getStatusInfo()).getMessage(),
			equalTo("599"));
	}

	/* The name that jakarta gives a status is the reason phrase of it, which is shown once and not again in brackets */
	@Property(tries = 100)
	public void shouldShowThePhraseOfAKnownStatusOnce(@ForAll Status status) {
		assertThat(new ResponseOddityException(status).getMessage(),
			equalTo(status.getStatusCode() + " " + status.getReasonPhrase()));
	}

	@Example
	public void shouldNotShowAnEmptyPhraseOfAKnownStatus() {
		assertThat(new ResponseOddityException(Response.status(404, "").build().getStatusInfo()).getMessage(),
			equalTo("404 Not Found"));
	}

	/* A node, or a proxy, may word a status in its own way, which is not lost for being worded differently */
	@Example
	public void shouldKeepThePhraseOfANodeThatWordsAKnownStatusDifferently() {
		assertThat(new ResponseOddityException(Response.status(404, "Nothing here").build().getStatusInfo())
			.getMessage(), equalTo("404 Not Found (Nothing here)"));
	}

	@Example
	public void shouldKeepNothingWhereThereIsNoBody() {
		assertThat(oddityOf(Response.status(Status.SERVICE_UNAVAILABLE).build()).getBody(), emptyString());
	}

	private static boolean isPlainTextAs(final String type) {
		return oddityOf(Response.status(Status.SERVICE_UNAVAILABLE).entity("This release does not include the part")
			.type(type).build()).isPlainText();
	}

	/* A node words a refusal that no field names as plain text, which whoever shows it tells from the page of a proxy
	   and from JSON by the type that the answer names */
	@Example
	public void shouldTellABodyThatWasSentAsPlainText() {
		for (final String type : List.of("text/plain", "text/plain;charset=UTF-8", "Text/Plain; charset=utf-8")) {
			assertThat(type, isPlainTextAs(type), is(true));
		}

		for (final String type : List.of("text/html", "application/json", "text/plainer")) {
			assertThat(type, isPlainTextAs(type), is(false));
		}

		assertThat(isPlainTextAs(null), is(false));
		assertThat(new ResponseOddityException(Status.SERVICE_UNAVAILABLE, "No type").isPlainText(), is(false));
		assertThat(new ResponseOddityException(Status.SERVICE_UNAVAILABLE).isPlainText(), is(false));
	}
}
