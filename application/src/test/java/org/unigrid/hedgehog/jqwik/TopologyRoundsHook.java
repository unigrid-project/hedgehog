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

package org.unigrid.hedgehog.jqwik;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.server.p2p.TopologyThread;

/*
   The topology thread logs a round that fails and goes on, so nothing else would fail a test for it, while the
   servers of a test run their topology threads all along. A property during which one of them loses a round fails,
   unless it makes rounds fail on purpose (FailsTopologyRounds). The hook wraps the injection of the servers, whose
   threads start their first round as they come up.
*/
public class TopologyRoundsHook implements AroundPropertyHook {
	private static final int AROUND_PROPERTY_PROXIMITY = -25;
	private static final String LOST_ROUND = "A round of the topology thread";

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	static {
		TopologyRoundsHook.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());
	}

	@Override
	public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
		if (context.findAnnotation(FailsTopologyRounds.class).isPresent()) {
			return property.execute();
		}

		final Logger logger = (Logger) LoggerFactory.getLogger(TopologyThread.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final PropertyExecutionResult result;

		appender.start();
		logger.addAppender(appender);

		try {
			result = property.execute();
		} finally {
			logger.detachAppender(appender);
		}

		final List<String> lost = lostRounds(appender);

		if (lost.isEmpty() || result.status() != PropertyExecutionResult.Status.SUCCESSFUL) {
			return result;
		}

		return result.mapToFailed(new AssertionError("The topology thread lost rounds: " + lost));
	}

	/* The appender takes events under its own lock, which a thread may still hold as it is detached */
	private static List<String> lostRounds(ListAppender<ILoggingEvent> appender) {
		synchronized (appender) {
			return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
				.filter(event -> event.getMessage().startsWith(LOST_ROUND)).map(ILoggingEvent::getFormattedMessage)
				.toList();
		}
	}

	@Override
	public int aroundPropertyProximity() {
		return AROUND_PROPERTY_PROXIMITY;
	}
}
