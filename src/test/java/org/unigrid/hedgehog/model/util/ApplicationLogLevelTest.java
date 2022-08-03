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
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.logging.Logger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeAll;

public class ApplicationLogLevelTest {
	private static ByteArrayOutputStream output = new ByteArrayOutputStream();

	@BeforeAll
	public static void setup() {
		output = new ByteArrayOutputStream();
		//System.setOut(new PrintStream(output));
	}

	@Property(tries = 5)
	public boolean shouldFilterLogMessagesByLogLevel(@ForAll @IntRange(max = 5) int logLevel,
		@ForAll @IntRange(max = 5) int printLevel) {

		final int previousSize = output.size();
		final Logger logger = Logger.getLogger(ApplicationLogLevelTest.class.getName());

		ApplicationLogLevel.configure(printLevel);
		//logger.setLevel(logLevel);
		

		//if (logLevel > 
		//System.out.println(logLevel + " : " + checkLevel);
		Level.
		return true;
	}
}
