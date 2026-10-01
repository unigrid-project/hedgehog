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
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.crypto.Signature;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class NetOptionsTest {
	private static final int PUBLIC_KEY_LENGTH = Signature.PUBLIC_KEY_HEX_SIZE * 2;
	private static final String PUBLIC_KEY = "\\p{XDigit}{" + PUBLIC_KEY_LENGTH + "}";

	@Provide
	public Arbitrary<List<String>> provideCommand() {
		return Arbitraries.of(List.of("bootstrap"), List.of("bootstrap", "info"), List.of("cli"), List.of("cli", "stop"),
			List.of("daemon")
		);
	}

	@Provide
	public Arbitrary<String> provideHost() {
		return OptionsCli.hosts();
	}

	@Provide
	public Arbitrary<List<String>> provideKeys() {
		return Arbitraries.strings().withChars("0123456789abcdef").ofMinLength(1).ofMaxLength(PUBLIC_KEY_LENGTH)
			.list().ofMinSize(1).ofMaxSize(5);
	}

	@Property
	public void shouldTakeTheAddressItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll("provideHost") String host, @ForAll @IntRange(min = 1, max = OptionsCli.MAX_PORT) int port,
		@ForAll boolean longNames) {

		OptionsCli.parse(command, longNames ? "--nethost" : "-H", host, longNames ? "--netport" : "-p",
			String.valueOf(port)
		);

		assertThat(NetOptions.getHost(), equalTo(host));
		assertThat(NetOptions.getPort(), equalTo(port));
	}

	@Property
	public void shouldListenEverywhereOnTheNetworkPortWithSeedsByDefault(@ForAll("provideCommand") List<String> command) {
		OptionsCli.parse(command);

		assertThat(NetOptions.getHost(), equalTo("0.0.0.0"));
		assertThat(NetOptions.getPort(), equalTo(NetOptions.DEFAULT_PORT));
		assertThat(NetOptions.isSeeds(), equalTo(true));
		assertThat(NetOptions.getNetworkKeys(), arrayWithSize(greaterThan(0)));
		assertThat(Stream.of(NetOptions.getNetworkKeys(), NetOptions.getRetiredNetworkKeys()).flatMap(Stream::of)
			.toList(), everyItem(matchesPattern(PUBLIC_KEY))
		);
	}

	@Property
	public void shouldUseSeedsUnlessToldNotTo(@ForAll("provideCommand") List<String> command,
		@ForAll Optional<Boolean> seeds) {

		OptionsCli.parse(command, seeds.map(on -> on ? "--seeds" : "--no-seeds").stream().toArray(String[]::new));
		assertThat(NetOptions.isSeeds(), equalTo(seeds.orElse(true)));
	}

	@Property
	public void shouldReplaceOnlyTheKeysItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll("provideKeys") List<String> keys, @ForAll boolean retired) {

		final Supplier<String[]> replaced = retired ? NetOptions::getRetiredNetworkKeys : NetOptions::getNetworkKeys;
		final Supplier<String[]> other = retired ? NetOptions::getNetworkKeys : NetOptions::getRetiredNetworkKeys;

		OptionsCli.parse(command);

		final List<String> untouched = List.of(other.get());

		OptionsCli.parse(command, (retired ? "--retired-network-keys=" : "--network-keys=") + String.join(",", keys));
		assertThat(List.of(replaced.get()), equalTo(keys));
		assertThat(List.of(other.get()), equalTo(untouched));
	}

	@Property
	public void shouldRefuseAPortThatIsNotANumber(@ForAll("provideCommand") List<String> command,
		@ForAll @AlphaChars @StringLength(min = 1, max = 10) String port) {

		assertThat(OptionsCli.refusal(command, "--netport", port).getMessage(), containsString("'" + port + "'"));
	}
}
