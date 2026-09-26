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

package org.unigrid.hedgehog.command.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDownload;

public class BootstrapFetchTest {
	private static final String RELEASES = "https://github.com/unigrid-project/hedgehog/releases/";
	private static final String SOURCE = "https://example.org/bootstrap.dat.gz";

	private final List<URL> installs = new ArrayList<>();
	private Path snapshot;

	@BeforeTry
	public void beforeTry() {
		installs.clear();
		snapshot = BootstrapCli.snapshotInMemory();
		downloadFails(null);
	}

	private void downloadFails(IOException problem) {
		new MockUp<SnapshotDownload>() {
			@Mock public /* static */ void install(URL source, Path target) throws IOException {
				if (problem != null) {
					throw problem;
				}

				installs.add(source);
			}
		};
	}

	@SneakyThrows
	private static URL url(String url) {
		return URI.create(url).toURL();
	}

	@Provide
	public Arbitrary<String> provideReleasedVersion() {
		final Arbitrary<Integer> part = Arbitraries.integers().between(0, 999);
		final Arbitrary<String> dev = Arbitraries.integers().between(0, 99).map(n -> "-dev." + n)
			.injectNull(0.5).map(suffix -> suffix == null ? "" : suffix);

		return Combinators.combine(part, part, part, dev).as((major, minor, patch, suffix)
			-> major + "." + minor + "." + patch + suffix);
	}

	@Provide
	public Arbitrary<String> provideUnreleasedVersion() {
		return Combinators.combine(provideReleasedVersion(), Arbitraries.of("-SNAPSHOT", "-rc1", "-beta", ".4", "-dev"))
			.as((version, suffix) -> version + suffix);
	}

	@Property(tries = 100)
	@SneakyThrows
	public void shouldFetchTheSnapshotOfItsOwnRelease(@ForAll("provideReleasedVersion") String version) {
		assertThat(BootstrapFetch.defaultUrl(version).toString(),
			equalTo(RELEASES + "download/v" + version + "/bootstrap.dat.gz"));
	}

	@Property(tries = 100)
	@SneakyThrows
	public void shouldFetchTheLatestSnapshotWhenUnreleased(@ForAll("provideUnreleasedVersion") String version) {
		assertThat(BootstrapFetch.defaultUrl(version).toString(), equalTo(RELEASES + "latest/download/bootstrap.dat.gz"));
	}

	@Example
	@SneakyThrows
	public void shouldInstallFromTheDefaultUrl() {
		final BootstrapCli.Result result = BootstrapCli.run("fetch");

		assertThat(result.exitCode(), equalTo(0));
		assertThat(result.out(), containsString("Installed " + snapshot));
		assertThat(installs, equalTo(List.of(BootstrapFetch.defaultUrl(Version.getVersionNumber()))));
	}

	@Example
	public void shouldInstallFromTheGivenUrl() {
		assertThat(BootstrapCli.run("fetch", "--url", SOURCE).exitCode(), equalTo(0));
		assertThat(installs, equalTo(List.of(url(SOURCE))));
	}

	@Example
	@SneakyThrows
	public void shouldLeaveAnExistingSnapshotAlone() {
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, new byte[] { 1 });

		final BootstrapCli.Result result = BootstrapCli.run("fetch");

		assertThat(result.exitCode(), equalTo(1));
		assertThat(result.err(), containsString("already exists, pass --force to replace it"));
		assertThat(installs, empty());
	}

	@Example
	@SneakyThrows
	public void shouldReplaceAnExistingSnapshotWhenForced() {
		Files.createDirectories(snapshot.getParent());
		Files.write(snapshot, new byte[] { 1 });

		assertThat(BootstrapCli.run("fetch", "--force").exitCode(), equalTo(0));
		assertThat(installs, equalTo(List.of(BootstrapFetch.defaultUrl(Version.getVersionNumber()))));
	}

	@Example
	public void shouldRefuseAnUnverifiableDownloadWithoutAStackTrace() {
		downloadFails(new IOException("The download does not match the hash published with it and was not installed"));

		final BootstrapCli.Result result = BootstrapCli.run("fetch", "--url", SOURCE);

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("was not installed"));
		result.assertNoStackTrace();
	}
}
