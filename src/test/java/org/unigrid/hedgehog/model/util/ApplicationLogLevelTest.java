/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.util;

import ch.qos.logback.classic.jul.JULHelper;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.java.Log;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.apache.commons.lang3.RandomStringUtils;

//@Log
public class ApplicationLogLevelTest {
	private static class JunitHandler extends Handler {
		@Getter @Setter private boolean dirty;

		@Override
		public void publish(LogRecord lr) {
			dirty = true;
		}

		@Override public void flush() { /* Ignored on purpose */ }
		@Override public void close() throws SecurityException { /* Ignored on purpose */ }
	}

	//@Property(tries = 10)
	public boolean shouldOutputLogMessagesByLogLevel(@ForAll @IntRange(min = 1, max = 5) int logLevel,
		@ForAll @IntRange(min = 0, max = 6) int messageLevel) {

		final java.util.logging.Level level = JULHelper.asJULLevel(ApplicationLogLevel.getLevelFromVerbosity(messageLevel));
		ApplicationLogLevel.configure(logLevel);

		final JunitHandler handler = new JunitHandler();
		//log.addHandler(handler);
		//log.log(level, RandomStringUtils.randomAscii(8));

		//if (handler.isDirty()) {
			return messageLevel <= logLevel;
		//} else {
		//	return messageLevel > logLevel;
		//}
	}
}
