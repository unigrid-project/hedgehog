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

package org.unigrid.hedgehog.command;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeContainerHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.option.GridnodeOptions;
import org.unigrid.hedgehog.model.option.LedgerOptions;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.option.PreviewOptions;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.option.S3Options;
import org.unigrid.hedgehog.model.option.SocksOptions;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;
import picocli.CommandLine;

/* A node started without the preview that was given every hidden option of the parts that this release leaves off
   which the daemon takes (DaemonTest), files that are not there and values that mean nothing among them. It starts the
   two servers of this release and nothing of the parts, and offers a peer the protocols of this release only, as it
   does without them. */
@AddLifecycleHook(DaemonHiddenOptionStartTest.AllHiddenOptions.class)
public class DaemonHiddenOptionStartTest extends BaseDefaultNodeTest {
	/* The files that the options name are of a file system in memory, which holds none of them */
	private static final FileSystem FILES = Jimfs.newFileSystem(Configuration.unix());

	/* The S3 gateway is left enabled and its lifecycle rounds are left on, so that a gateway which started would show:
	   --no-s3 and an interval of zero only switch off */
	private static final List<String> HIDDEN = List.of("--ledger-key", "/keys/ledger.key", "--ledger-genesis",
		"/genesis.json", "--ledger-trusted=garbage", "--announce-address", "garbage", "--socks-host", "0.0.0.0",
		"--socks-country", "SE", "--socks-geoip", "/geoip/country.mmdb", "--s3domain", "s3.example.org",
		"--s3cachedir", "/cache", "--s3cachesize", "1", "--s3cachehalflife", "PT1H", "--s3lifecycleinterval", "PT1M",
		"--s3root", "root", "--s3plain");

	/* Parses what the hook of the base has, on the ports it found free, and the options above besides. The higher
	   proximity makes it run after that hook, and the container starts with the first example, after both. */
	public static class AllHiddenOptions implements BeforeContainerHook {
		@Override
		public void beforeContainer(ContainerLifecycleContext context) {
			final List<String> arguments = new ArrayList<>(List.of("daemon", "--nethost", NetOptions.getHost(),
				"--netport", String.valueOf(NetOptions.getPort()), "--resthost", RestOptions.getHost(), "--restport",
				String.valueOf(RestOptions.getPort()), "--resttoken", RestOptions.getToken(), "--no-seeds",
				"--s3host", S3Options.getHost(), "--s3port", String.valueOf(S3Options.getPort())));

			arguments.addAll(HIDDEN);

			new CommandLine(Hedgehog.class).registerConverter(Path.class, FILES::getPath)
				.parseArgs(arguments.toArray(String[]::new));
		}

		@Override
		public int beforeContainerProximity() {
			return 10;
		}
	}

	/* Without this the examples below would hold for a node that was never given the options */
	@Example
	public void shouldHoldTheOptionsItWasGiven() {
		assertThat(PreviewOptions.isPreview(), is(false));
		assertThat(LedgerOptions.getLedgerKeyFile(), equalTo(FILES.getPath("/keys/ledger.key")));
		assertThat(LedgerOptions.getLedgerGenesisFile(), equalTo(FILES.getPath("/genesis.json")));
		assertThat(LedgerOptions.getLedgerTrusted(), equalTo("garbage"));
		assertThat(GridnodeOptions.getAnnounceAddress(), equalTo("garbage"));
		assertThat(SocksOptions.getHost(), equalTo("0.0.0.0"));
		assertThat(SocksOptions.getCountry(), equalTo(Optional.of("SE")));
		assertThat(SocksOptions.getGeoip(), equalTo(FILES.getPath("/geoip/country.mmdb")));
		assertThat(S3Options.isEnabled(), is(true));
		assertThat(S3Options.getDomain(), equalTo("s3.example.org"));
		assertThat(S3Options.getCacheDirectory(), equalTo(FILES.getPath("/cache")));
		assertThat(S3Options.getCacheSize(), equalTo(1L));
		assertThat(S3Options.getRoot(), equalTo(Optional.of("root")));
		assertThat(S3Options.isPlain(), is(true));
	}

	@Example
	public void shouldStartTheServersOfThisReleaseAlone() {
		assertThat(isCreated(P2PServer.class), is(true));
		assertThat(isCreated(RestServer.class), is(true));
		assertThat(createdOfThePreview(), is(empty()));
		assertThat(threadsOfThePreview(), is(empty()));
	}

	@Example
	public void shouldOfferTheProtocolsOfThisReleaseOnly() {
		assertThat(Network.hasLedger(), is(false));
		assertThat(Network.hasLedger(Network.getProtocols()), is(false));
		assertThat(Network.hasPreview(Network.getProtocols()), is(false));
		assertThat(createdOfThePreview(), is(empty()));
		assertThat(threadsOfThePreview(), is(empty()));
	}
}
