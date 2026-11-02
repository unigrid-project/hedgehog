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

package org.unigrid.hedgehog.model;

import java.util.Optional;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.command.option.LedgerOptions;
import org.unigrid.hedgehog.command.option.PreviewOptions;
import org.unigrid.hedgehog.command.option.SocksOptions;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PreviewSetup {

	/* Runs before the container starts, like the other setups. A node told to run a part that this release leaves off
	   is refused, as it would otherwise start without what it was asked to run. The S3 options are left alone, so a
	   node started with them before still starts. */
	public static void validate() {
		final Optional<String> given = Stream.of(named("--ledger", LedgerOptions.isLedger()),
			named("--gridnode", GridnodeOptions.getGridnodeKeyFile() != null),
			named("--socks-port", SocksOptions.getPort() != SocksOptions.OFF)
		).flatMap(Optional::stream).findFirst();

		if (given.isPresent() && !PreviewOptions.isPreview()) {
			throw new IllegalArgumentException(given.get() + " is not part of this release");
		}
	}

	private static Optional<String> named(String option, boolean given) {
		return Optional.of(option).filter(name -> given);
	}
}
