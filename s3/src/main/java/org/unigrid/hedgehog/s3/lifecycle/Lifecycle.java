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

package org.unigrid.hedgehog.s3.lifecycle;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.unigrid.hedgehog.s3.entity.AbortIncompleteMultipartUpload;
import org.unigrid.hedgehog.s3.entity.LifecycleAnd;
import org.unigrid.hedgehog.s3.entity.LifecycleConfiguration;
import org.unigrid.hedgehog.s3.entity.LifecycleExpiration;
import org.unigrid.hedgehog.s3.entity.LifecycleFilter;
import org.unigrid.hedgehog.s3.entity.LifecycleRule;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.NoncurrentVersionExpiration;
import org.unigrid.hedgehog.s3.entity.S3Xml;
import org.unigrid.hedgehog.s3.entity.Tag;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;

/* Reads lifecycle configurations into rules, and refuses what S3 refuses of them */
public final class Lifecycle {
	public static final int MAX_RULES = 1000;

	private static final int MAX_ID = 255;
	private static final int MAX_NEWER_VERSIONS = 100;
	private static final String ENABLED = "Enabled";
	private static final String DISABLED = "Disabled";

	private Lifecycle() {
		/* Static helpers only */
	}

	public static List<Rule> rules(final LifecycleConfiguration configuration) {
		final List<LifecycleRule> rules = configuration.getRules();
		final List<String> ids = rules.stream().map(LifecycleRule::getId).filter(Objects::nonNull).toList();

		if (rules.isEmpty()) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		} else if (rules.size() > MAX_RULES) {
			throw new S3Exception(S3ErrorCode.InvalidRequest,
				"A lifecycle configuration holds at most " + MAX_RULES + " rules.");
		} else if (Set.copyOf(ids).size() < ids.size()) {
			throw invalid("Rule ID must be unique. Found same ID for more than one rule");
		}

		return rules.stream().map(Lifecycle::rule).toList();
	}

	/* The rules a bucket applies, from the configuration it keeps, which was checked when it was kept */
	public static List<Rule> enabled(final Bucket bucket) throws IOException, MalformedXmlException {
		final String document = bucket.configurations().get(Bucket.LIFECYCLE);

		return Objects.isNull(document) ? List.of() : rules(S3Xml.read(LifecycleConfiguration.class,
			new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)))).stream().filter(Rule::enabled)
			.toList();
	}

	private static Rule rule(final LifecycleRule rule) {
		if (Objects.nonNull(rule.getId()) && rule.getId().length() > MAX_ID) {
			throw invalid("ID length should not exceed allowed limit of " + MAX_ID);
		} else if (Stream.of(rule.getExpiration(), rule.getNoncurrentVersionExpiration(),
			rule.getAbortIncompleteMultipartUpload()).allMatch(Objects::isNull)) {

			throw new S3Exception(S3ErrorCode.InvalidRequest,
				"At least one action needs to be specified in a rule");
		}

		final Filter filter = filter(rule);
		final Rule.RuleBuilder built = Rule.builder().id(rule.getId()).enabled(enabled(rule.getStatus()))
			.filter(filter);

		Optional.ofNullable(rule.getExpiration()).ifPresent(expiration -> expiration(built, expiration, filter));
		Optional.ofNullable(rule.getNoncurrentVersionExpiration()).ifPresent(noncurrent -> noncurrent(built,
			noncurrent));
		Optional.ofNullable(rule.getAbortIncompleteMultipartUpload()).ifPresent(abort -> abort(built, abort,
			filter));

		return built.build();
	}

	private static boolean enabled(final String status) {
		if (!ENABLED.equals(status) && !DISABLED.equals(status)) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		return ENABLED.equals(status);
	}

	/* An expiration names either a number of days, a date or the removal of expired delete markers */
	private static void expiration(final Rule.RuleBuilder rule, final LifecycleExpiration expiration,
		final Filter filter) {

		if (Stream.of(expiration.getDays(), expiration.getDate(), expiration.getExpiredObjectDeleteMarker())
			.filter(Objects::nonNull).count() != 1) {

			throw new S3Exception(S3ErrorCode.MalformedXML);
		} else if (Objects.nonNull(expiration.getDate()) && !expiration.getDate().truncatedTo(ChronoUnit.DAYS)
			.equals(expiration.getDate())) {

			throw invalid("'Date' must be at midnight GMT");
		} else if (Objects.nonNull(expiration.getExpiredObjectDeleteMarker()) && !filter.tags().isEmpty()) {
			throw invalid("ExpiredObjectDeleteMarker cannot be specified with tags");
		}

		rule.expirationDays(Optional.ofNullable(expiration.getDays()).map(days -> days(days, "Days")).orElse(0))
			.expirationDate(expiration.getDate())
			.expiredDeleteMarkers(Boolean.TRUE.equals(expiration.getExpiredObjectDeleteMarker()));
	}

	private static void noncurrent(final Rule.RuleBuilder rule, final NoncurrentVersionExpiration noncurrent) {
		final int newer = Objects.requireNonNullElse(noncurrent.getNewerNoncurrentVersions(), 0);

		if (Objects.nonNull(noncurrent.getNewerNoncurrentVersions()) && (newer < 1 || newer > MAX_NEWER_VERSIONS)) {
			throw invalid("'NewerNoncurrentVersions' must be an integer from 1 to " + MAX_NEWER_VERSIONS);
		}

		rule.noncurrentDays(days(noncurrent.getNoncurrentDays(), "NoncurrentDays")).newerNoncurrentVersions(newer);
	}

	private static void abort(final Rule.RuleBuilder rule, final AbortIncompleteMultipartUpload abort,
		final Filter filter) {

		if (!filter.tags().isEmpty()) {
			throw new S3Exception(S3ErrorCode.InvalidRequest,
				"AbortIncompleteMultipartUpload cannot be specified with Tags.");
		}

		rule.abortDays(days(abort.getDaysAfterInitiation(), "DaysAfterInitiation"));
	}

	private static int days(final Integer days, final String name) {
		if (Objects.isNull(days) || days <= 0) {
			throw invalid("'" + name + "' in a lifecycle rule must be a positive integer");
		}

		return days;
	}

	/* A rule names its filter or, the way older clients do, only a prefix */
	private static Filter filter(final LifecycleRule rule) {
		final LifecycleFilter filter = rule.getFilter();

		if (Objects.nonNull(filter) && Objects.nonNull(rule.getPrefix())) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		} else if (Objects.isNull(filter)) {
			return bounded(rule.getPrefix(), List.of(), null, null);
		} else if (Stream.of(filter.getPrefix(), filter.getTag(), filter.getObjectSizeGreaterThan(),
			filter.getObjectSizeLessThan(), filter.getAnd()).filter(Objects::nonNull).count() > 1) {

			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		final LifecycleAnd and = filter.getAnd();

		return Objects.isNull(and) ? bounded(filter.getPrefix(), Stream.ofNullable(filter.getTag()).toList(),
			filter.getObjectSizeGreaterThan(), filter.getObjectSizeLessThan()) : bounded(and.getPrefix(),
			and.getTags(), and.getObjectSizeGreaterThan(), and.getObjectSizeLessThan());
	}

	private static Filter bounded(final String prefix, final List<Tag> tags, final Long greaterThan,
		final Long lessThan) {

		final long above = Objects.requireNonNullElse(greaterThan, -1L);
		final long below = Objects.requireNonNullElse(lessThan, Long.MAX_VALUE);

		if (Objects.nonNull(greaterThan) && above < 0 || below <= 0 || above >= below) {
			throw invalid("Object sizes in a lifecycle filter must not be negative, and ObjectSizeGreaterThan "
				+ "must be less than ObjectSizeLessThan");
		}

		return new Filter(Objects.requireNonNullElse(prefix, ""), tags(tags), above, below);
	}

	private static Map<String, String> tags(final List<Tag> tags) {
		final Map<String, String> collected = new LinkedHashMap<>();

		for (Tag tag : tags) {
			if (Objects.isNull(tag.getKey()) || collected.containsKey(tag.getKey())) {
				throw invalid("The tags of a lifecycle filter need keys, and keys of their own");
			}

			collected.put(tag.getKey(), Objects.requireNonNullElse(tag.getValue(), ""));
		}

		return collected;
	}

	private static S3Exception invalid(final String message) {
		return new S3Exception(S3ErrorCode.InvalidArgument, message);
	}
}
