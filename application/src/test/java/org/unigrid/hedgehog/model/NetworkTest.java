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

package org.unigrid.hedgehog.model;

import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class NetworkTest {
	private static final String[] EARLIER_RELEASE = { "hedgehog/0.0.4", "gridspork/0.0.4" };
	private static final String SPORK_PROTOCOL = "hedgehog/0.0.9";
	private static final List<String> DAEMON = List.of("daemon");

	/* The ledger protocol comes first, then the protocol of this release, and a node of an earlier release is left to
	   settle on the one it offers first */
	@Example
	public void offersTheLedgerProtocolFirstAndTheProtocolsOfTheEarlierReleaseAfterItWhileTheLedgerIsOn() {
		OptionsCli.parse(DAEMON, "--ledger");

		assertThat(Network.getProtocols(), arrayContaining("hedgehog-ledger/0.0.1", SPORK_PROTOCOL,
			EARLIER_RELEASE[0], EARLIER_RELEASE[1]));
		assertThat(Network.LEDGER_PROTOCOL, is(Network.getProtocols()[0]));
	}

	/* A node that did not opt in offers the protocol of this release and what the earlier release did, so no ledger
	   connection is ever settled on */
	@Example
	public void offersTheProtocolOfThisReleaseAndThoseOfTheEarlierReleaseWhileTheLedgerIsOff() {
		OptionsCli.parse(DAEMON);

		assertThat(Network.getProtocols(), arrayContaining(SPORK_PROTOCOL, EARLIER_RELEASE[0], EARLIER_RELEASE[1]));
	}

	@Example
	public void followsTheLedgerOptionWhenAskedWhetherThereIsALedger() {
		OptionsCli.parse(DAEMON);
		assertThat(Network.hasLedger(), is(false));

		OptionsCli.parse(DAEMON, "--ledger");
		assertThat(Network.hasLedger(), is(true));
	}

	@Example
	public void tellsWhetherTheLedgerProtocolIsOffered() {
		OptionsCli.parse(DAEMON, "--ledger");
		assertThat(Network.hasLedger(Network.getProtocols()), is(true));

		OptionsCli.parse(DAEMON);
		assertThat(Network.hasLedger(Network.getProtocols()), is(false));

		assertThat(Network.hasLedger(EARLIER_RELEASE), is(false));
		assertThat(Network.hasLedger(new String[0]), is(false));
	}
}
