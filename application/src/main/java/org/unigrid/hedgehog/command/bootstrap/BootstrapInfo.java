/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.io.IOException;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.bootstrap.SnapshotInfo;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import picocli.CommandLine.Command;

@Command(name = "info", description = "Show what the converted bootstrap snapshot contains.")
public class BootstrapInfo implements Callable<Integer> {
	@Override
	public Integer call() throws IOException {
		final SnapshotInfo info = SnapshotReader.open(SnapshotOptions.getSnapshot()).getInfo();

		System.out.println("Tip hash:          " + info.getTipHash());
		System.out.println("Tip height:        " + info.getTipHeight());
		System.out.println("Addresses:         " + info.getAddressCount());
		System.out.println("Ledger entries:    " + info.getEntryCount());
		System.out.println("Transactions:      " + info.getTransactionCount());
		System.out.println("Total unspent:     " + info.getTotalUnspent());
		System.out.println("Zerocoin minted:   " + info.getZerocoinMinted());
		System.out.println("Built:             " + info.getBuilt());
		System.out.println("Signature:         " + info.getSignature());
		return 0;
	}
}
