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

package org.unigrid.hedgehog.model.util;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class TerminalTextTest {
	@Example
	public void shouldDropWhatATerminalWouldActOn() {
		assertThat(TerminalText.printable("a\u001b[2Jb\u009b31mc\rd\u0007e\u007ff\u0000gh‮i⁦j"),
			equalTo("a[2Jb31mcdefghij"));
	}

	@Example
	public void shouldKeepLinesTabsAndTextOfAnyScript() {
		final String text = "Stored data:\n{ \"name\" :\t\"Åsa Ünal 東京 🚀\" }\n";

		assertThat(TerminalText.printable(text), equalTo(text));
	}

	@Property
	public void shouldLeaveNothingATerminalActsOnButLineBreaksAndTabs(@ForAll String text) {
		assertThat(TerminalText.printable(text).codePoints().allMatch(point -> point == '\n' || point == '\t'
			|| !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT), is(true));
	}
}
