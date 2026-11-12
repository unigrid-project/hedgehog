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

package org.unigrid.hedgehog.command;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;

/* What anything logs as an error while this is open. The command line sets the level of the root logger from -v and
   leaves it there, so the level is put back as this closes. */
public final class ErrorLog implements AutoCloseable {
	private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
	private final Level before = root.getLevel();
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	public ErrorLog() {
		appender.start();
		root.addAppender(appender);
	}

	/* A thread of the node may log while the list is read, and an appender takes one event at a time */
	public List<ILoggingEvent> events() {
		synchronized (appender) {
			return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)).toList();
		}
	}

	/* The exceptions that were logged, each followed by its causes down to the first one */
	public List<List<IThrowableProxy>> traces() {
		return events().stream().map(ILoggingEvent::getThrowableProxy).filter(Objects::nonNull)
			.map(thrown -> Stream.iterate(thrown, Objects::nonNull, IThrowableProxy::getCause).toList()).toList();
	}

	@Override
	public void close() {
		root.detachAppender(appender);
		root.setLevel(before);
	}
}
