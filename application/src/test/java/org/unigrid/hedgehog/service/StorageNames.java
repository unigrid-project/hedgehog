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

package org.unigrid.hedgehog.service;

import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StorageNames {
	/* Never created or touched; the tests only compare paths against it */
	public static final Path DATA_DIR = Path.of("/hedgehog-test/s3data");

	public static Arbitrary<String> escaping() {
		return Arbitraries.of("", ".", "..", "../x", "a/..", "a/../..", "a/../../b", "/etc", "/etc/passwd",
			"../../etc/passwd", "./..", ".//..", "a/./../..", "a\u0000b"
		);
	}

	public static Arbitrary<String> any() {
		return Arbitraries.strings().withChars('a', 'b', '.', '/', '\\').ofMinLength(0).ofMaxLength(12);
	}

	public static Arbitrary<String> plain() {
		return Arbitraries.strings().alpha().numeric().withChars('-', '_').ofMinLength(1).ofMaxLength(32);
	}
}
