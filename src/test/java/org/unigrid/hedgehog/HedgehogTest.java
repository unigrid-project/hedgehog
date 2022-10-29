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

package org.unigrid.hedgehog;

import java.util.Arrays;
import net.jqwik.api.Example;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import picocli.CommandLine;

public class HedgehogTest {
	@Example
	public void shouldRetainHeaderWidth() {
		final String header[] = new CommandLine(Hedgehog.class).getCommandSpec().usageMessage().header();
		long headersWithSameWidth = Arrays.stream(header).filter( a -> a.length() == header[1].length()).count();

		assertThat("The help header does not retain the same width on all lines",
			(int) headersWithSameWidth, is(header.length - 2)
		);
	}
}
