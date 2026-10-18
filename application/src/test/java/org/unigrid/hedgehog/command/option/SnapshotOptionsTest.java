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
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SnapshotOptionsTest {
	@Provide
	public Arbitrary<List<String>> provideCommand() {
		return Arbitraries.of(List.of("bootstrap"), List.of("bootstrap", "info"), List.of("bootstrap", "fetch"));
	}

	@Property
	public void shouldUseTheSnapshotItIsGivenOrTheOneInTheDataDirectory(@ForAll("provideCommand") List<String> command,
		@ForAll Optional<@AlphaChars @StringLength(min = 1, max = 12) String> snapshot, @ForAll boolean longName) {

		OptionsCli.parse(command, snapshot.map(name -> new String[] { longName ? "--snapshot" : "-s", name })
			.orElse(new String[0])
		);

		assertThat(SnapshotOptions.getSnapshot(), equalTo(snapshot.map(Path::of)
			.orElse(ApplicationDirectory.create().getUserDataDir().resolve("bootstrap.dat")))
		);
	}
}
