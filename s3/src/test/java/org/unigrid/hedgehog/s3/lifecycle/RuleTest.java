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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.index.BlobRef;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Part;
import org.unigrid.hedgehog.s3.index.Upload;
import org.unigrid.hedgehog.s3.spi.BlobId;

public class RuleTest {
	private static ObjectVersion version(final String key, final Instant created, final Map<String, String> tags,
		final long size) {

		return ObjectVersion.builder().bucket("bucket").key(key).lastModified(created).tags(tags)
			.parts(List.of(Part.builder().number(1).slices(List.of(new BlobRef(new BlobId("blob"), 0, size)))
			.build())).build();
	}

	private static ObjectVersion version(final Instant created) {
		return version("key", created, Map.of(), 1);
	}

	@Provide
	Arbitrary<Instant> instants() {
		return Arbitraries.longs().between(0, 4_000_000_000L).map(Instant::ofEpochSecond);
	}

	/* S3 counts the days from the moment a version was written, rounded up to the next midnight UTC */
	@Property
	public void expiresAtTheMidnightAfterItsDaysHavePassed(@ForAll("instants") final Instant created,
		@ForAll @IntRange(min = 1, max = 3650) final int days) {

		final Instant due = created.truncatedTo(ChronoUnit.DAYS).plus(days + 1, ChronoUnit.DAYS);
		final Rule rule = Rule.builder().filter(Filter.ALL).expirationDays(days).noncurrentDays(days)
			.abortDays(days).build();
		final Upload upload = Upload.builder().template(version(created)).build();

		assertThat(rule.expires(version(created), due.minusMillis(1)), equalTo(false));
		assertThat(rule.expires(version(created), due), equalTo(true));
		assertThat(rule.removesMarker(version(created), due.minusMillis(1)), equalTo(false));
		assertThat(rule.removesMarker(version(created), due), equalTo(true));
		assertThat(rule.expiresNoncurrent(version(Instant.EPOCH), created, 0, due.minusMillis(1)), equalTo(false));
		assertThat(rule.expiresNoncurrent(version(Instant.EPOCH), created, 0, due), equalTo(true));
		assertThat(rule.aborts(upload, due.minusMillis(1)), equalTo(false));
		assertThat(rule.aborts(upload, due), equalTo(true));
	}

	@Property
	public void expiresOnItsDateWhateverTheAgeOfTheVersion(@ForAll("instants") final Instant created,
		@ForAll("instants") final Instant date) {

		final Rule rule = Rule.builder().filter(Filter.ALL).expirationDate(date).build();

		assertThat(rule.expires(version(created), date.minusMillis(1)), equalTo(false));
		assertThat(rule.expires(version(created), date), equalTo(true));
		assertThat(rule.removesMarker(version(created), date), equalTo(false));
	}

	@Property
	public void keepsTheNewestNoncurrentVersionsItIsToldToKeep(@ForAll @IntRange(max = 100) final int keep,
		@ForAll @IntRange(max = 200) final int newer) {

		final Rule rule = Rule.builder().filter(Filter.ALL).noncurrentDays(1).newerNoncurrentVersions(keep).build();
		final Instant later = Instant.EPOCH.plus(3, ChronoUnit.DAYS);

		assertThat(rule.expiresNoncurrent(version(Instant.EPOCH), Instant.EPOCH, newer, later),
			equalTo(newer >= keep));
	}

	@Example
	public void appliesToTheVersionsItsFilterNames() {
		final Filter filter = new Filter("logs/", Map.of("env", "test"), 10, 20);
		final Instant now = Instant.EPOCH;

		assertThat(filter.matches(version("logs/a", now, Map.of("env", "test", "team", "s3"), 15)), equalTo(true));
		assertThat(filter.matches(version("data/a", now, Map.of("env", "test"), 15)), equalTo(false));
		assertThat(filter.matches(version("logs/a", now, Map.of("env", "prod"), 15)), equalTo(false));
		assertThat(filter.matches(version("logs/a", now, Map.of("env", "test"), 10)), equalTo(false));
		assertThat(filter.matches(version("logs/a", now, Map.of("env", "test"), 20)), equalTo(false));
		assertThat(Filter.ALL.matches(version("anything", now, Map.of(), 0)), equalTo(true));
		assertThat(Rule.builder().filter(filter).abortDays(1).build().aborts(Upload.builder().template(
			version("data/a", now, Map.of(), 0)).build(), now.plus(10, ChronoUnit.DAYS)), equalTo(false));
	}
}
