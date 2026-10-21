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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/* What a class logs while it is open, at any level down to debug */
public final class DebugLog implements AutoCloseable {
	private final Logger logger;
	private final Level before;
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	public DebugLog(final Class<?> source) {
		logger = (Logger) LoggerFactory.getLogger(source);
		before = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		appender.start();
		logger.addAppender(appender);
	}

	public List<ILoggingEvent> events() {
		return List.copyOf(appender.list);
	}

	@Override
	public void close() {
		logger.detachAppender(appender);
		logger.setLevel(before);
	}
}
