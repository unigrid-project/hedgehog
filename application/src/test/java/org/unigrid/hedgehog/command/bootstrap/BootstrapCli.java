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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import mockit.Mock;
import mockit.MockUp;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.SnapshotOptions;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BootstrapCli {
	private static final Objenesis OBJENESIS = new ObjenesisStd();

	public static HedgehogCli.Result run(String... args) {
		return HedgehogCli.run(Stream.concat(Stream.of("bootstrap"), Stream.of(args)).toArray(String[]::new));
	}

	/* A stand-in for classes whose constructor needs a real snapshot; a MockUp answers its methods */
	public static <T> T withoutConstructor(Class<T> type) {
		return OBJENESIS.newInstance(type);
	}

	/* The commands only ever see this path, so nothing they check or write reaches the real disk */
	public static Path snapshotInMemory() {
		final Path snapshot = Jimfs.newFileSystem(Configuration.unix()).getPath("/data/bootstrap.dat");

		new MockUp<SnapshotOptions>() {
			@Mock public /* static */ Path getSnapshot() {
				return snapshot;
			}

			@Mock public /* static */ Path defaultSnapshot() {
				return snapshot;
			}
		};

		return snapshot;
	}
}
