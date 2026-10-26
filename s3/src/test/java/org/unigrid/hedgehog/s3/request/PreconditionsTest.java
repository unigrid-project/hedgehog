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

package org.unigrid.hedgehog.s3.request;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.request.Outcome.NOT_MODIFIED;
import static org.unigrid.hedgehog.s3.request.Outcome.PRECONDITION_FAILED;
import static org.unigrid.hedgehog.s3.request.Outcome.PROCEED;

public class PreconditionsTest {
	private static final String ETAG = "\"abc123\"";
	private static final String OTHER_ETAG = "\"def456\"";
	private static final Instant LAST_MODIFIED = Instant.parse("2026-10-18T12:00:00Z");
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

	private record Scenario(String ifMatch, String ifNoneMatch, String ifModifiedSince, String ifUnmodifiedSince) { }

	@Example
	public void evaluatesEveryReadHeaderCombination() {
		for (final Scenario scenario : scenarios()) {
			assertThat(Preconditions.forRead(scenario.ifMatch(), scenario.ifNoneMatch(),
				scenario.ifModifiedSince(), scenario.ifUnmodifiedSince(), ETAG, LAST_MODIFIED),
				equalTo(expectedReadOutcome(scenario)));
		}
	}

	@Example
	public void copySourceNeverAnswersNotModified() {
		for (final Scenario scenario : scenarios()) {
			final Outcome expected = expectedReadOutcome(scenario) == PROCEED ? PROCEED
				: PRECONDITION_FAILED;

			assertThat(Preconditions.forCopySource(scenario.ifMatch(), scenario.ifNoneMatch(),
				scenario.ifModifiedSince(), scenario.ifUnmodifiedSince(), ETAG, LAST_MODIFIED),
				equalTo(expected));
		}
	}

	@Example
	public void ifMatchDisablesIfUnmodifiedSince() {
		assertThat(Preconditions.forRead(ETAG, null, null, httpDate(LAST_MODIFIED.minusSeconds(60)),
			ETAG, LAST_MODIFIED), equalTo(PROCEED));
	}

	@Example
	public void ifNoneMatchDisablesIfModifiedSince() {
		assertThat(Preconditions.forRead(null, OTHER_ETAG, httpDate(LAST_MODIFIED.plusSeconds(60)),
			null, ETAG, LAST_MODIFIED), equalTo(PROCEED));
	}

	@Example
	public void failureWinsOverNotModified() {
		assertThat(Preconditions.forRead(OTHER_ETAG, ETAG, null, null, ETAG, LAST_MODIFIED),
			equalTo(PRECONDITION_FAILED));
	}

	@Example
	public void comparesEntityTagsWithoutQuotesOrWeakness() {
		assertThat(Preconditions.forRead("W/\"abc123\"", null, null, null, ETAG, LAST_MODIFIED),
			equalTo(PROCEED));
		assertThat(Preconditions.forRead("abc123", null, null, null, ETAG, LAST_MODIFIED), equalTo(PROCEED));
		assertThat(Preconditions.forRead("\"other\", W/abc123", null, null, null, ETAG, LAST_MODIFIED),
			equalTo(PROCEED));
		assertThat(Preconditions.forRead("*", null, null, null, ETAG, LAST_MODIFIED), equalTo(PROCEED));
		assertThat(Preconditions.forRead(null, "*", null, null, ETAG, LAST_MODIFIED), equalTo(NOT_MODIFIED));
	}

	@Example
	public void comparesDatesAtWholeSeconds() {
		final Instant withMillis = LAST_MODIFIED.plusMillis(500);

		assertThat(Preconditions.forRead(null, null, httpDate(LAST_MODIFIED), null, ETAG, withMillis),
			equalTo(NOT_MODIFIED));
		assertThat(Preconditions.forRead(null, null, null, httpDate(LAST_MODIFIED), ETAG, withMillis),
			equalTo(PROCEED));
	}

	@Example
	public void ignoresUnparseableDates() {
		assertThat(Preconditions.forRead(null, null, "yesterday", "tomorrow", ETAG, LAST_MODIFIED),
			equalTo(PROCEED));
	}

	@Example
	public void writeOutcomesFollowConditionalHeaders() {
		assertThat(Preconditions.forWrite(null, "*", Optional.of(ETAG)),
			equalTo(WriteOutcome.PRECONDITION_FAILED));
		assertThat(Preconditions.forWrite(null, "*", Optional.empty()), equalTo(WriteOutcome.PROCEED));
		assertThat(Preconditions.forWrite(ETAG, null, Optional.of(ETAG)), equalTo(WriteOutcome.PROCEED));
		assertThat(Preconditions.forWrite("W/\"abc123\"", null, Optional.of(ETAG)),
			equalTo(WriteOutcome.PROCEED));
		assertThat(Preconditions.forWrite(OTHER_ETAG, null, Optional.of(ETAG)),
			equalTo(WriteOutcome.PRECONDITION_FAILED));
		assertThat(Preconditions.forWrite(ETAG, null, Optional.empty()), equalTo(WriteOutcome.NOT_FOUND));
		assertThat(Preconditions.forWrite(null, null, Optional.empty()), equalTo(WriteOutcome.PROCEED));
	}

	private static List<Scenario> scenarios() {
		final List<String> etags = Arrays.asList(null, ETAG, OTHER_ETAG);
		final List<String> dates = Arrays.asList(null, httpDate(LAST_MODIFIED.plusSeconds(60)),
			httpDate(LAST_MODIFIED.minusSeconds(60)), "not a date");
		final List<Scenario> scenarios = new ArrayList<>();

		for (final String ifMatch : etags) {
			for (final String ifNoneMatch : etags) {
				for (final String ifModifiedSince : dates) {
					for (final String ifUnmodifiedSince : dates) {
						scenarios.add(new Scenario(ifMatch, ifNoneMatch, ifModifiedSince,
							ifUnmodifiedSince));
					}
				}
			}
		}

		return scenarios;
	}

	private static Outcome expectedReadOutcome(final Scenario scenario) {
		if (hasFailedMatch(scenario)) {
			return PRECONDITION_FAILED;
		}

		return hasUnchangedMatch(scenario) ? NOT_MODIFIED : PROCEED;
	}

	private static boolean hasFailedMatch(final Scenario scenario) {
		if (present(scenario.ifMatch())) {
			return !etagMatches(scenario.ifMatch());
		}

		return violated(scenario.ifUnmodifiedSince());
	}

	private static boolean hasUnchangedMatch(final Scenario scenario) {
		if (present(scenario.ifNoneMatch())) {
			return etagMatches(scenario.ifNoneMatch());
		}

		return notModified(scenario.ifModifiedSince());
	}

	private static boolean present(final String header) {
		return header != null && !header.isBlank();
	}

	private static boolean etagMatches(final String header) {
		return Arrays.stream(header.split(","))
			.map(String::trim)
			.map(tag -> tag.replace("\"", "").replace("W/", ""))
			.anyMatch("abc123"::equals);
	}

	private static boolean violated(final String date) {
		return parseDate(date).filter(when -> LAST_MODIFIED.isAfter(when)).isPresent();
	}

	private static boolean notModified(final String date) {
		return parseDate(date).filter(when -> !LAST_MODIFIED.isAfter(when)).isPresent();
	}

	private static Optional<Instant> parseDate(final String header) {
		if (header == null) {
			return Optional.empty();
		}

		try {
			return Optional.of(Instant.from(HTTP_DATE.parse(header)));
		} catch (DateTimeException e) {
			return Optional.empty();
		}
	}

	private static String httpDate(final Instant instant) {
		return HTTP_DATE.format(instant.atZone(ZoneOffset.UTC));
	}
}
