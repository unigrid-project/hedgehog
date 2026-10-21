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

package org.unigrid.hedgehog.service.ledger;

import jakarta.inject.Inject;
import java.util.List;
import java.util.OptionalLong;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.service.ledger.LedgerService.Report;

/* The service as the container makes it, with the collaborators the container injects, in a node that was not told to
   run a ledger */
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerServiceCdiOffTest extends BaseMockedWeldTest {
	@Inject private LedgerService service;

	@Example
	public void shouldBeABeanThatHoldsNothingWhileTheLedgerIsOff() {
		OptionsCli.parse(List.of("daemon"));

		assertThat(service.status(), equalTo(new Report(false, false, "", false, 0, "", 0, List.of(), List.of())));
		assertThat(service.height(), equalTo(OptionalLong.empty()));
		assertThat(Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName()
			.startsWith("ledger-")).toList(), empty());
	}
}
