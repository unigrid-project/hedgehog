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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.PrintStream;
import java.util.function.IntConsumer;
import lombok.RequiredArgsConstructor;

/* One line for every tenth of the transfer, so a pipe or a log stays readable. */
@RequiredArgsConstructor
class DownloadProgress implements IntConsumer {
	private static final int STEP = 10;

	private final PrintStream out;
	private int next = STEP;

	@Override
	public void accept(int percent) {
		if (percent >= next) {
			out.println("Downloading " + percent + "%");
			next = (percent / STEP + 1) * STEP;
		}
	}
}
