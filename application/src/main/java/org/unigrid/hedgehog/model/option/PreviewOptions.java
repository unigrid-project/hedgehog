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

package org.unigrid.hedgehog.model.option;

import lombok.Getter;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/* Hidden, as it is meant for test networks and tests, not for the nodes this release runs */
public class PreviewOptions {

	@Getter @Option(names = "--preview", scope = CommandLine.ScopeType.INHERIT, hidden = true, defaultValue = "false",
		description = "Turn on the parts of the node that this release leaves off."
	)
	private static boolean preview;
}
