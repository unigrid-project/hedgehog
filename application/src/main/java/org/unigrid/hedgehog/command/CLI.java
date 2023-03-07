/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import org.unigrid.hedgehog.command.cli.GridSporkGet;
import org.unigrid.hedgehog.command.cli.GridSporkGrow;
import org.unigrid.hedgehog.command.cli.GridSporkList;
import org.unigrid.hedgehog.command.cli.GridSporkSet;
import org.unigrid.hedgehog.command.cli.NodeAdd;
import org.unigrid.hedgehog.command.cli.NodeList;
import org.unigrid.hedgehog.command.cli.NodeRemove;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "cli", subcommands = { GridSporkGet.class, GridSporkGrow.class, GridSporkSet.class, GridSporkList.class,
	NodeAdd.class, NodeRemove.class, NodeList.class
})
public class CLI {
	@Mixin private NetOptions netOptions;
	@Mixin private RestOptions restOptions;
}
