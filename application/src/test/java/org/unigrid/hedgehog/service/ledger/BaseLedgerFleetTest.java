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

import jakarta.enterprise.inject.spi.CDI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import mockit.Delegate;
import mockit.Expectations;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.jboss.weld.environment.se.WeldContainer;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.server.BaseServerTest;

/* The base of a test on a fleet of ledger nodes. The fleet is started by the first example that asks for it and is
   shared by the examples of the class, as the start of the servers takes most of the time of a class; it is closed
   when the class is done. Each of the 20 servers of BaseServerTest has its own container, and the fleet uses the
   first of them for its nodes. */
public abstract class BaseLedgerFleetTest extends BaseServerTest {
	private static LedgerFleet fleet;
	private static Throwable failure;

	private final Map<String, Integer> ports = new HashMap<>();

	@Override
	protected boolean isolateContainers() {
		return true;
	}

	@Override
	protected boolean runsLedger() {
		return true;
	}

	/* What the fleet looks like; a class that wants another overrides this */
	protected LedgerFleet.Settings settings() {
		return LedgerFleet.Settings.builder().build();
	}

	protected final LedgerFleet fleet() {
		synchronized (BaseLedgerFleetTest.class) {
			if (Objects.nonNull(failure)) {
				throw new IllegalStateException("The fleet could not be started", failure);
			}

			if (Objects.isNull(fleet)) {
				fleet = started();
			}

			return fleet;
		}
	}

	/* A fleet that cannot be started is not tried again by each example of the class */
	private LedgerFleet started() {
		try {
			return LedgerFleet.start(settings(), servers(), this::containerName);
		} catch (RuntimeException | Error ex) {
			failure = ex;
			throw ex;
		}
	}

	/* The servers of a test share one process and so one NetOptions, while each node says the port of its own in its
	   hello. Each container is given the port of its own server. */
	@BeforeTry
	void giveEachContainerItsOwnPort() {
		for (int i = 0; i < servers().size(); i++) {
			ports.put(containerName(i), servers().get(i).getP2p().getPort());
		}

		new Expectations() {{
			NetOptions.getPort(); minTimes = 0;
			result = new Delegate<Integer>() {
				int port() {
					return ports.get(((WeldContainer) CDI.current()).getId());
				}
			};
		}};
	}

	@BeforeTry
	void mockTheSurroundingsOfTheFleet() {
		LedgerFleet.mockEnvironment();
	}

	@AfterContainer
	static void closeTheFleet() {
		synchronized (BaseLedgerFleetTest.class) {
			failure = null;

			if (Objects.nonNull(fleet)) {
				fleet.close();
				fleet = null;
			}
		}
	}
}
