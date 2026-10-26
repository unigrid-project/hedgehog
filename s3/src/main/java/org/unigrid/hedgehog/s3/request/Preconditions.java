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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

public final class Preconditions {
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;
	private static final String WILDCARD = "*";

	private Preconditions() {
		/* Static helpers only */
	}

	public static Outcome forRead(final String ifMatch, final String ifNoneMatch,
		final String ifModifiedSince, final String ifUnmodifiedSince, final String etag,
		final Instant lastModified) {

		if (failedByMatch(ifMatch, ifUnmodifiedSince, etag, lastModified)) {
			return Outcome.PRECONDITION_FAILED;
		}

		return unchanged(ifNoneMatch, ifModifiedSince, etag, lastModified) ? Outcome.NOT_MODIFIED
			: Outcome.PROCEED;
	}

	public static Outcome forCopySource(final String ifMatch, final String ifNoneMatch,
		final String ifModifiedSince, final String ifUnmodifiedSince, final String etag,
		final Instant lastModified) {

		return forRead(ifMatch, ifNoneMatch, ifModifiedSince, ifUnmodifiedSince, etag, lastModified)
			== Outcome.PROCEED ? Outcome.PROCEED : Outcome.PRECONDITION_FAILED;
	}

	public static WriteOutcome forWrite(final String ifMatch, final String ifNoneMatch,
		final Optional<String> currentEtag) {

		if (currentEtag.isPresent() && matchesWildcard(ifNoneMatch)) {
			return WriteOutcome.PRECONDITION_FAILED;
		}

		return conditionalWrite(ifMatch, currentEtag);
	}

	private static WriteOutcome conditionalWrite(final String ifMatch,
		final Optional<String> currentEtag) {
		if (!present(ifMatch)) {
			return WriteOutcome.PROCEED;
		}

		if (currentEtag.isEmpty()) {
			return WriteOutcome.NOT_FOUND;
		}

		return matches(ifMatch, currentEtag.get()) ? WriteOutcome.PROCEED
			: WriteOutcome.PRECONDITION_FAILED;
	}

	private static boolean failedByMatch(final String ifMatch, final String ifUnmodifiedSince,
		final String etag, final Instant lastModified) {

		if (present(ifMatch)) {
			return !matches(ifMatch, etag);
		}

		return parseDate(ifUnmodifiedSince)
			.filter(date -> lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(date))
			.isPresent();
	}

	private static boolean unchanged(final String ifNoneMatch, final String ifModifiedSince,
		final String etag, final Instant lastModified) {

		if (present(ifNoneMatch)) {
			return matches(ifNoneMatch, etag);
		}

		return parseDate(ifModifiedSince)
			.filter(date -> !lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(date))
			.isPresent();
	}

	private static boolean matchesWildcard(final String header) {
		return present(header) && Arrays.stream(header.split(","))
			.map(String::trim)
			.anyMatch(WILDCARD::equals);
	}

	private static boolean matches(final String header, final String etag) {
		if (!present(header)) {
			return false;
		}

		final String current = etag == null ? null : entityTag(etag);

		return Arrays.stream(header.split(","))
			.map(Preconditions::entityTag)
			.anyMatch(tag -> WILDCARD.equals(tag) || tag.equals(current));
	}

	private static String entityTag(final String value) {
		final String trimmed = value.trim();

		return unquoted(trimmed.startsWith("W/") ? trimmed.substring(2) : trimmed);
	}

	private static String unquoted(final String tag) {
		return tag.length() >= 2 && tag.startsWith("\"") && tag.endsWith("\"")
			? tag.substring(1, tag.length() - 1) : tag;
	}

	private static Optional<Instant> parseDate(final String header) {
		if (!present(header)) {
			return Optional.empty();
		}

		try {
			return Optional.of(Instant.from(HTTP_DATE.parse(header)));
		} catch (DateTimeException e) {
			return Optional.empty();
		}
	}

	private static boolean present(final String header) {
		return header != null && !header.isBlank();
	}
}
