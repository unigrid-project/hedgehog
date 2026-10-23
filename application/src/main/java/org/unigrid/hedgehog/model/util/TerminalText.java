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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* What a node sends is shown to an operator who decides on it, and a terminal acts on control characters: they can
   clear the screen, move the cursor or overwrite a line, and the characters that reorder text can make it read as
   something else, so none of them reaches the terminal. Line breaks stay, as the data shown spans lines, and so do
   tabs, which are spaces. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TerminalText {
	public static String printable(String text) {
		final StringBuilder printable = new StringBuilder(text.length());

		text.codePoints().filter(TerminalText::isPrintable).forEach(printable::appendCodePoint);
		return printable.toString();
	}

	private static boolean isPrintable(int point) {
		return point == '\n' || point == '\t'
			|| !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT;
	}
}
