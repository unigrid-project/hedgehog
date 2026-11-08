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

package org.unigrid.hedgehog.model.option;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import picocli.CommandLine;
import picocli.CommandLine.Option;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PreviewOptionsTest {
	private static final List<String> DAEMON = List.of("daemon");

	@Example
	public void shouldBeOffByDefault() {
		OptionsCli.parse(DAEMON);

		assertThat(PreviewOptions.isPreview(), equalTo(false));
	}

	@Example
	public void shouldSwitchThePreviewOnWithoutAValue() {
		OptionsCli.parse(DAEMON, "--preview");

		assertThat(PreviewOptions.isPreview(), equalTo(true));
	}

	@Property(tries = 20)
	public void shouldNotTakeAValueForTheSwitch(@ForAll @AlphaChars @StringLength(min = 1, max = 12) String value) {
		assertThat(OptionsCli.refusal(DAEMON, "--preview=" + value).getMessage(), containsString("--preview"));
	}

	/* Only test networks and tests are meant to run with it, so the help of the daemon leaves it out */
	@Example
	public void shouldBeLeftOutOfTheHelpOfTheDaemon() {
		final HedgehogCli.Result help = HedgehogCli.run("daemon", "--help");

		assertThat(new CommandLine(Hedgehog.class).getSubcommands().get("daemon").getCommandSpec()
			.findOption("--preview").hidden(), equalTo(true));

		assertThat(help.err(), help.exitCode(), equalTo(0));
		assertThat(help.out(), containsString("--restport"));
		assertThat(help.out(), not(containsString("--preview")));
	}

	@Provide
	public Arbitrary<Field> provideOptionOfAPartItTurnsOn() {
		return Arbitraries.of(Stream.of(GridnodeOptions.class, LedgerOptions.class, S3Options.class, SocksOptions.class)
			.flatMap(type -> Arrays.stream(type.getDeclaredFields()))
			.filter(field -> field.isAnnotationPresent(Option.class)).toList()
		);
	}

	/* Without the switch the parts these options are for stay off, so the help leaves them out as well */
	@Property
	public void shouldLeaveTheOptionsOfThePartsItTurnsOnOutOfTheHelpOfTheDaemon(
		@ForAll("provideOptionOfAPartItTurnsOn") Field field) {

		final CommandLine daemon = new CommandLine(Hedgehog.class).getSubcommands().get("daemon");
		final String[] names = field.getAnnotation(Option.class).names();
		final String name = names[names.length - 1];

		assertThat(name, daemon.getCommandSpec().findOption(name).hidden(), equalTo(true));
		assertThat(daemon.getUsageMessage(), not(containsString(name)));
	}
}
