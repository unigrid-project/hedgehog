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
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.matchesPattern;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import picocli.CommandLine;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class NetOptionsTest {
	private static final int PUBLIC_KEY_LENGTH = Signature.PUBLIC_KEY_HEX_SIZE * 2;
	private static final String PUBLIC_KEY = "\\p{XDigit}{" + PUBLIC_KEY_LENGTH + "}";

	/* What a node admitted in all before the operator could say */
	private static final int DEFAULT_MAX_INBOUND = 2000;

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
		assertThat(List.of(NetOptions.getNetworkKeys()), everyItem(matchesPattern(PUBLIC_KEY)));
	}

	@Property
	public void shouldUseSeedsUnlessToldNotTo(@ForAll("provideCommand") List<String> command,
		@ForAll Optional<Boolean> seeds) {

		OptionsCli.parse(command, seeds.map(on -> on ? "--seeds" : "--no-seeds").stream().toArray(String[]::new));
		assertThat(NetOptions.isSeeds(), equalTo(seeds.orElse(true)));
	}

	@Property
	public void shouldReplaceTheKeysItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll("provideKeys") List<String> keys) {

		OptionsCli.parse(command, "--network-keys=" + String.join(",", keys));
		assertThat(List.of(NetOptions.getNetworkKeys()), equalTo(keys));
	}

	/* A seed, which every node dials, holds more connections than a node that few know, so the operator says how many
	   peers may hold to the node at a time */
	@Property(tries = 20)
	public void shouldTakeTheCeilingOfInboundConnectionsItIsGiven(@ForAll("provideCommand") List<String> command,
		@ForAll @IntRange(min = ConnectionLimiter.MAX_PER_ADDRESS) int ceiling) {

		OptionsCli.parse(command, "--netmaxinbound", String.valueOf(ceiling));
		assertThat(NetOptions.getMaxInbound(), equalTo(ceiling));
	}

	/* A node that is not given the option admits what it did before there was one: the connections of a topology
	   that is full */
	@Property(tries = 5)
	public void shouldAdmitTheConnectionsOfAFullTopologyByDefault(@ForAll("provideCommand") List<String> command) {
		OptionsCli.parse(command);

		assertThat(NetOptions.getMaxInbound(), equalTo(ConnectionLimiter.MAX_IN_ALL));
		assertThat(NetOptions.getMaxInbound(), equalTo(DEFAULT_MAX_INBOUND));
	}

	/* One address may hold that many, so a node that admitted fewer in all would give no address its share */
	@Example
	public void shouldTakeACeilingOfWhatOneAddressMayHold() {
		OptionsCli.parse(List.of("daemon"), "--netmaxinbound", String.valueOf(ConnectionLimiter.MAX_PER_ADDRESS));
		assertThat(NetOptions.getMaxInbound(), equalTo(ConnectionLimiter.MAX_PER_ADDRESS));
	}

	@Property(tries = 20)
	public void shouldRefuseACeilingBelowWhatOneAddressMayHold(@ForAll("provideCommand") List<String> command,
		@ForAll @IntRange(min = Integer.MIN_VALUE, max = ConnectionLimiter.MAX_PER_ADDRESS - 1) int ceiling) {

		final String message = OptionsCli.refusal(command, "--netmaxinbound=" + ceiling).getMessage();

		assertThat(message, containsString("'--netmaxinbound'"));
		assertThat(message, containsString("Not " + ConnectionLimiter.MAX_PER_ADDRESS + " or more"));
	}

	@Property(tries = 20)
	public void shouldRefuseACeilingThatIsNotANumber(@ForAll("provideCommand") List<String> command,
		@ForAll @AlphaChars @StringLength(min = 1, max = 10) String ceiling) {

		assertThat(OptionsCli.refusal(command, "--netmaxinbound", ceiling).getMessage(),
			containsString("'" + ceiling + "' is not an int"));
	}

	/* What the operator of a seed reads in the help of the daemon: what the ceiling counts and what it leaves out,
	   what it is when the option is not given, and that a seed may need more */
	@Example
	public void shouldSayInTheHelpWhatTheCeilingCountsAndThatASeedMayNeedMore() {
		final CommandLine daemon = new CommandLine(Hedgehog.class).getSubcommands().get("daemon");
		final String help = daemon.getUsageMessage(CommandLine.Help.Ansi.OFF).replaceAll("\\s+", " ");

		assertThat(daemon.getCommandSpec().findOption("--netmaxinbound").hidden(), equalTo(false));
		assertThat(help, containsString("--netmaxinbound"));
		assertThat(help, containsString("peers open to this node"));
		assertThat(help, containsString("not the ones it dials"));
		assertThat(help, containsString("none from the loopback address"));
		assertThat(help, containsString(ConnectionLimiter.MAX_PER_ADDRESS + " or more"));
		assertThat(help, containsString("defaults to " + DEFAULT_MAX_INBOUND));
		assertThat(help, containsString("A seed, which every node dials, may need more."));
	}

	/* A key that the board no longer holds signs nothing the network trusts, so there is no option to name one */
	@Property(tries = 10)
	public void shouldRefuseRetiredNetworkKeys(@ForAll("provideCommand") List<String> command,
		@ForAll("provideKeys") List<String> keys) {

		assertThat(OptionsCli.refusal(command, "--retired-network-keys=" + String.join(",", keys)).getMessage(),
			containsString("--retired-network-keys"));
	}

	@Property
	public void shouldRefuseAPortThatIsNotANumber(@ForAll("provideCommand") List<String> command,
		@ForAll @AlphaChars @StringLength(min = 1, max = 10) String port) {

		assertThat(OptionsCli.refusal(command, "--netport", port).getMessage(), containsString("'" + port + "'"));
	}
}
