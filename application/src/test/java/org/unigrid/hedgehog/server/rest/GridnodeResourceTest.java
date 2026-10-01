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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.SneakyThrows;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.model.Collateral;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;
import org.unigrid.hedgehog.model.network.Topology;

public class GridnodeResourceTest extends BaseRestClientTest {
	private static final String ADDRESS = "127.0.0.1:5000";

	@Mocked private GridnodeOptions gridnodeOptions;
	@Inject private Topology topology;

	/* The identity bean is created once per container from the first key file it sees, so the key is shared */
	private static final Signature key = newKey();

	private FileSystem fileSystem;

	@SneakyThrows
	private static Signature newKey() {
		return new Signature();
	}

	/* The identity is created from the options, so they have to be in place before the server starts */
	@Override
	@BeforeProperty
	@SneakyThrows
	public void before() {
		fileSystem = Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix").build());

		final Path keyFile = fileSystem.getPath("/gridnode.key");

		Files.writeString(keyFile, "Private Key: " + key.getPrivateKey() + "\nPublic Key: " + key.getPublicKey());
		Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

		new Expectations() {{
			GridnodeOptions.getGridnodeKeyFile(); result = keyFile; minTimes = 0;
			GridnodeOptions.getAnnounceAddress(); result = ADDRESS; minTimes = 0;
		}};

		super.before();
	}

	@AfterProperty
	@SneakyThrows
	public void closeFileSystem() {
		fileSystem.close();
	}

	@Example
	@SneakyThrows
	public void shouldActivateTheOwnGridnode() {
		assertThat(client.put("/gridnode/start", Entity.text("")).getStatus(), is(202));

		final Gridnode own = topology.findGridnode(key.getPublicKey()).get();

		assertThat(own.getStatus(), is(Gridnode.Status.ACTIVE));
		assertThat(own.getHostName(), is(ADDRESS));
		assertThat(GridnodeSignature.verifies(own), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldDeactivateTheOwnGridnode() {
		client.put("/gridnode/start", Entity.text(""));

		assertThat(client.put("/gridnode/stop", Entity.text("")).getStatus(), is(202));
		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	@SneakyThrows
	public void shouldListTheGridnodesWithoutTheirSignature() {
		client.put("/gridnode/start", Entity.text(""));

		final String json = client.get("/gridnode").readEntity(String.class);

		assertThat(json, containsString(key.getPublicKey()));
		assertThat(json, containsString("\"timestamp\""));
		assertThat(json, not(containsString("signature")));
	}

	@Example
	@SneakyThrows
	public void shouldReturnTheCollateralOfTheActiveGridnodes() {
		client.put("/gridnode/start", Entity.text(""));

		final long active = topology.cloneGridnode().stream()
			.filter(g -> g.getStatus() == Gridnode.Status.ACTIVE).count();

		assertThat(client.get("/gridnode/collateral").readEntity(Double.class),
			is(new Collateral().get((int) active)));
	}
}
