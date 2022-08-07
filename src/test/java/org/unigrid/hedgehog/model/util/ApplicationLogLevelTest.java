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

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.Map;
import lombok.extern.java.Log;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

@Log
public class ApplicationLogLevelTest {
	private static final Map<?,?> LEVELS_UTILS = ArrayUtils.toMap(new Object[][]{
		{ 0, java.util.logging.Level.OFF     }, { 1, java.util.logging.Level.SEVERE },
		{ 2, java.util.logging.Level.WARNING }, { 3, java.util.logging.Level.INFO   },
		{ 4, java.util.logging.Level.FINE    }, { 5, java.util.logging.Level.FINER  },
		{ 6, java.util.logging.Level.ALL     }
	});

	private static ByteArrayOutputStream output = new ByteArrayOutputStream();

	@BeforeAll
	public static void setup() {
		System.setOut(new PrintStream(new ByteArrayOutputStream()));
	}

	@Property(tries = 10)
	public boolean shouldOutputLogMessagesByLogLevel(@ForAll @IntRange(max = 5) int logLevel,
		@ForAll @IntRange(max = 5) int printLevel) {

		ApplicationLogLevel.configure(printLevel);

		final int previousSize = output.size();
		log.log((java.util.logging.Level) LEVELS_UTILS.get(logLevel), RandomStringUtils.randomAscii(8));

		return (previousSize > output.size() && printLevel >= logLevel) || (logLevel == 0 && printLevel == 0);
	}

	@AfterAll
	public static void teardown() {
		output = new ByteArrayOutputStream();
		System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
	}
}
