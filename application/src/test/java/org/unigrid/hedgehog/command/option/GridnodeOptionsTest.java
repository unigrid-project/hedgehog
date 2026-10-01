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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridnodeOptionsTest {
	private static final List<String> DAEMON = List.of("daemon");

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Property
	public void shouldTakeTheKeyFileAndAddressItIsGiven(
		@ForAll @Size(min = 1, max = 4) List<@AlphaChars @StringLength(min = 1, max = 12) String> keyFile,
		@ForAll("provideHost") String host, @ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port,
		@ForAll boolean longName) {

		final Path path = Path.of("/", keyFile.toArray(String[]::new));

		OptionsCli.parse(DAEMON, longName ? "--gridnode" : "-G", path.toString(), "--announce-address", host + ":" + port);

		assertThat(GridnodeOptions.getGridnodeKeyFile(), equalTo(path));
		assertThat(GridnodeOptions.getAnnounceAddress(), equalTo(host + ":" + port));
	}

	@Property
	public void shouldBeAPlainNodeWithoutAKeyFile(
		@ForAll Optional<@AlphaChars @StringLength(min = 1, max = 12) String> keyFile) {

		OptionsCli.parse(DAEMON, keyFile.map(name -> new String[] { "-G", name }).orElse(new String[0]));

		assertThat(Optional.ofNullable(GridnodeOptions.getGridnodeKeyFile()), equalTo(keyFile.map(Path::of)));
		assertThat(GridnodeOptions.getAnnounceAddress(), nullValue());
	}
}
