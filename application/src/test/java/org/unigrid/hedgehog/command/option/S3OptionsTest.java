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

package org.unigrid.hedgehog.command.option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class S3OptionsTest {
	private static final List<String> DAEMON = List.of("daemon");

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Property
	public void shouldTakeTheAddressAndDomainItIsGiven(@ForAll("provideHost") String host,
		@ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port, @ForAll("provideHost") String domain) {

		OptionsCli.parse(DAEMON, "--s3host", host, "--s3port", String.valueOf(port), "--s3domain", domain);

		assertThat(S3Options.getHost(), equalTo(host));
		assertThat(S3Options.getPort(), equalTo(port));
		assertThat(S3Options.getDomain(), equalTo(domain));
	}

	@Example
	public void shouldServeLocallyOverTlsWithoutADomainByDefault() {
		OptionsCli.parse(DAEMON);

		assertThat(S3Options.getHost(), equalTo("localhost"));
		assertThat(S3Options.getPort(), equalTo(S3Options.DEFAULT_PORT));
		assertThat(S3Options.getDomain(), nullValue());
		assertThat(S3Options.isPlain(), equalTo(false));
	}

	@Example
	public void shouldServePlainHttpWhenAsked() {
		OptionsCli.parse(DAEMON, "--s3plain");
		assertThat(S3Options.isPlain(), equalTo(true));
	}

	@Property
	public void shouldTakeTheCacheItIsGiven(@ForAll @AlphaChars @StringLength(min = 1, max = 12) String directory,
		@ForAll @LongRange(min = 0) long size, @ForAll @IntRange(min = 1) int minutes) {

		OptionsCli.parse(DAEMON, "--s3cachedir", directory, "--s3cachesize", String.valueOf(size),
			"--s3cachehalflife", Duration.ofMinutes(minutes).toString()
		);

		assertThat(S3Options.getCacheDirectory(), equalTo(Path.of(directory)));
		assertThat(S3Options.getCacheSize(), equalTo(size));
		assertThat(S3Options.getCacheHalfLife(), equalTo(Duration.ofMinutes(minutes)));
	}

	@Example
	public void shouldCacheInTheDataDirectoryByDefault() {
		OptionsCli.parse(DAEMON);

		assertThat(S3Options.getCacheDirectory(),
			equalTo(ApplicationDirectory.create().getUserDataDir().resolve("s3").resolve("cache"))
		);
		assertThat(S3Options.getCacheSize(), equalTo(S3Options.DEFAULT_CACHE_SIZE));
		assertThat(S3Options.getCacheHalfLife(), equalTo(Duration.ofDays(1)));
	}

	@Example
	public void shouldKeepTheIndexOfItsOwnNamespaceInTheDataDirectory() {
		OptionsCli.parse(DAEMON);

		assertThat(S3Options.getIndexDirectory(),
			equalTo(ApplicationDirectory.create().getUserDataDir().resolve("s3").resolve("index"))
		);
		assertThat(S3Options.getRoot(), equalTo(Optional.empty()));
	}

	@Property
	public void shouldTakeTheRootToMount(@ForAll @AlphaChars @StringLength(min = 1, max = 64) String root) {
		OptionsCli.parse(DAEMON, "--s3root", root);
		assertThat(S3Options.getRoot(), equalTo(Optional.of(root)));
	}

	@Property
	public void shouldRefuseAHalfLifeThatIsNotADuration(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String value) {
		assertThat(OptionsCli.refusal(DAEMON, "--s3cachehalflife", value).getMessage(),
			containsString("--s3cachehalflife")
		);
	}

	@Property
	public void shouldRefuseAPortThatIsNotANumber(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String value) {
		assertThat(OptionsCli.refusal(DAEMON, "--s3port", value).getMessage(),
			containsString("--s3port': '" + value + "'")
		);
	}
}
