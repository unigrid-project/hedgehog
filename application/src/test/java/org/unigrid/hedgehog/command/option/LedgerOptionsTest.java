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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import picocli.CommandLine.ParameterException;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerOptionsTest {
	private static final List<String> DAEMON = List.of("daemon");

	private static Path pathOf(List<String> names) {
		return Path.of("/", names.toArray(String[]::new));
	}

	@Example
	public void shouldBeOffAndWithoutFilesByDefault() {
		OptionsCli.parse(DAEMON);

		assertThat(LedgerOptions.isLedger(), equalTo(false));
		assertThat(LedgerOptions.getLedgerKeyFile(), nullValue());
		assertThat(LedgerOptions.getLedgerGenesisFile(), nullValue());
		assertThat(LedgerOptions.getLedgerTrusted(), nullValue());
	}

	@Example
	public void shouldSwitchTheLedgerOnWithoutAValue() {
		OptionsCli.parse(DAEMON, "--ledger");

		assertThat(LedgerOptions.isLedger(), equalTo(true));
	}

	@Property
	public void shouldTakeTheFilesAndTheCheckpointItIsGiven(
		@ForAll @Size(min = 1, max = 4) List<@AlphaChars @StringLength(min = 1, max = 12) String> keyFile,
		@ForAll @Size(min = 1, max = 4) List<@AlphaChars @StringLength(min = 1, max = 12) String> genesisFile,
		@ForAll @AlphaChars @StringLength(min = 1, max = 40) String trusted) {

		OptionsCli.parse(DAEMON, "--ledger", "--ledger-key", pathOf(keyFile).toString(), "--ledger-genesis",
			pathOf(genesisFile).toString(), "--ledger-trusted", trusted
		);

		assertThat(LedgerOptions.isLedger(), equalTo(true));
		assertThat(LedgerOptions.getLedgerKeyFile(), equalTo(pathOf(keyFile)));
		assertThat(LedgerOptions.getLedgerGenesisFile(), equalTo(pathOf(genesisFile)));
		assertThat(LedgerOptions.getLedgerTrusted(), equalTo(trusted));
	}

	@Property
	public void shouldHoldNothingBeyondWhatTheCommandIsGiven(@ForAll boolean ledger,
		@ForAll Optional<@AlphaChars @StringLength(min = 1, max = 12) String> keyFile,
		@ForAll Optional<@AlphaChars @StringLength(min = 1, max = 12) String> trusted) {

		final List<String> given = new ArrayList<>();

		if (ledger) {
			given.add("--ledger");
		}

		keyFile.ifPresent(name -> given.addAll(List.of("--ledger-key", name)));
		trusted.ifPresent(value -> given.addAll(List.of("--ledger-trusted", value)));
		OptionsCli.parse(DAEMON, given.toArray(String[]::new));

		assertThat(LedgerOptions.isLedger(), equalTo(ledger));
		assertThat(Optional.ofNullable(LedgerOptions.getLedgerKeyFile()), equalTo(keyFile.map(Path::of)));
		assertThat(LedgerOptions.getLedgerGenesisFile(), nullValue());
		assertThat(Optional.ofNullable(LedgerOptions.getLedgerTrusted()), equalTo(trusted));
	}

	@Property
	public void shouldNotTakeAValueForTheSwitch(@ForAll @AlphaChars @StringLength(min = 1, max = 12) String value) {
		final ParameterException refusal = OptionsCli.refusal(DAEMON, "--ledger=" + value);

		assertThat(refusal.getMessage(), containsString("--ledger"));
	}

	@Example
	public void shouldNeedAValueForEachFileAndTheCheckpoint() {
		for (String option : List.of("--ledger-key", "--ledger-genesis", "--ledger-trusted")) {
			assertThat(option, OptionsCli.refusal(DAEMON, option).getMessage(), containsString(option));
		}
	}
}
