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

package org.unigrid.hedgehog.server.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.model.NodeStatus;
import org.unigrid.hedgehog.model.bootstrap.BlockFixture;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;
import org.unigrid.hedgehog.model.bootstrap.SnapshotBuilder;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInstaller;
import org.unigrid.hedgehog.model.bootstrap.SnapshotSignature;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.ReleaseKeyFixture;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.server.rest.entity.StatusResponse;

public class UtilResourceTest extends BaseRestClientTest {
	@Inject
	private NodeStatus nodeStatus;

	@Inject
	private SnapshotInstaller snapshotInstaller;

	@Inject
	private BootstrapSnapshot snapshot;

	@BeforeTry
	public void run() {
		nodeStatus.running();
	}

	@Example
	@SneakyThrows
	public void shouldReportRunningWhenIdle() {
		assertThat(client.getEntity("/status", StatusResponse.class),
			equalTo(new StatusResponse("running", NodeStatus.COMPLETE)));
	}

	@Example
	@SneakyThrows
	public void shouldAnswerVersionWithOk() {
		final Response response = client.get("/version");

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.OK));
		assertThat(response.readEntity(String.class), containsString(Version.getVersionNumber()));
	}

	@Example
	@SneakyThrows
	public void shouldReportTheProgressOfADownload() {
		nodeStatus.downloading(42);
		assertThat(client.getEntity("/status", StatusResponse.class), equalTo(new StatusResponse("downloading", 42)));
	}

	@Example
	@SneakyThrows
	public void shouldInstallAMissingSnapshotAndRunAgain() {
		Files.deleteIfExists(snapshot.getPath());
		snapshotInstaller.installIfMissing(signedSnapshot().toUri().toURL());

		assertThat(Files.exists(snapshot.getPath()), equalTo(true));
		assertThat(client.getEntity("/status", StatusResponse.class),
			equalTo(new StatusResponse("running", NodeStatus.COMPLETE)));
	}

	@Example
	@SneakyThrows
	public void shouldRunWithoutASnapshotWhenTheDownloadFails() {
		Files.deleteIfExists(snapshot.getPath());
		snapshotInstaller.installIfMissing(Path.of("absent-bootstrap.dat").toUri().toURL());

		assertThat(Files.exists(snapshot.getPath()), equalTo(false));
		assertThat(nodeStatus.current().activity(), equalTo(NodeStatus.Activity.RUNNING));
	}

	@SneakyThrows
	private static Path signedSnapshot() {
		final Path path = Files.createTempFile("hhg-status-", ".dat");
		final Signature key = new Signature();

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { key.getPublicKey() };
			}
		};

		SnapshotSignature.signAndAppend(path, key.getPrivateKey());
		ReleaseKeyFixture.trusted().publish(path);
		return path;
	}
}
