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

package org.unigrid.hedgehog.command;

import org.unigrid.hedgehog.command.bootstrap.BootstrapBalance;
import org.unigrid.hedgehog.command.bootstrap.BootstrapHistory;
import org.unigrid.hedgehog.command.bootstrap.BootstrapImport;
import org.unigrid.hedgehog.command.bootstrap.BootstrapInfo;
import org.unigrid.hedgehog.command.bootstrap.BootstrapSign;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "bootstrap",
	subcommands = { BootstrapImport.class, BootstrapInfo.class, BootstrapBalance.class,
		BootstrapHistory.class, BootstrapSign.class },
	description = "Convert the legacy chain bootstrap into a snapshot and query addresses in it."
)
public class Bootstrap {
	@Mixin private NetOptions netOptions;
	@Mixin private SnapshotOptions snapshotOptions;
}
