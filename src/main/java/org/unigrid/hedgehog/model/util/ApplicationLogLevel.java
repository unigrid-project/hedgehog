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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.LoggerFactory;

public class ApplicationLogLevel {
	private static final Map<?,?> LEVELS = ArrayUtils.toMap(new Object[][]{
		{ 0, Level.OFF   }, { 1, Level.ERROR }, { 2, Level.WARN }, { 3, Level.INFO },
		{ 4, Level.DEBUG }, { 5, Level.TRACE }, { 6, Level.ALL  }
	});

	public static Level getLevelFromVerbosity(int verbosity) {
		return verbosity < 6 ? (Level) LEVELS.get(verbosity) : Level.ALL;
	}

	public static int getVerbosityFromLevel(Level level) throws UnsupportedLogLevelException {
		final Integer verbosity = (Integer) MapUtils.invertMap(LEVELS).get(level);

		if (Objects.isNull(level)) {
			throw new UnsupportedLogLevelException(String.format("Can't find verbosity for %s.", level));
		}
	
		return verbosity;
	}

	public static void configure(int verbosity) {
		final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
		root.setLevel(getLevelFromVerbosity(verbosity));
	}
}
