/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.nativeimage.windows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUID;
import org.unigrid.hedgehog.nativeimage.windows.Shell32Wrapper.Header;

@CLibrary("shell32")
@CContext(Header.class)
public class Shell32Wrapper {
	public static class Header implements CContext.Directives {
		@Override
		public List<String> getHeaderFiles() {
			return List.of("<windows.h>", "<knownfolders.h>", "<shlobj.h>");
		}
	}

	private static int length(CCharPointer utf16String) {
		final byte[] last = { 0x42, 0x42 }; /* As long as we don't start at zero, these numbers don't matter */

		for (int i = 0; i < Integer.MAX_VALUE; i++) {
			last[i % 2] = utf16String.read(i);

			/* End of string detected */
			if (Arrays.equals(last, new byte[] {0x00, 0x00})) {
				return i / 2 - 2;
			}
		}

		return -1; /* Shouldn't really be able to happen */
	}

	public static String getKnownFolderPath(GUID guid) throws WindowsException {
	        final CCharPointerPointer location = UnmanagedMemory.calloc(8); /* 64 bit */
		final int result = SHGetKnownFolderPath(guid, 0, WordFactory.nullPointer(), location);

		if (result != 0) {
			throw new WindowsException("Unable to find Windows path with SHGetKnownFolderPath()", result);
		}

		final int pathLength = length(location.read());

		final String folderPath = CTypeConversion.toJavaString(location.read(),
			WordFactory.unsigned(pathLength), StandardCharsets.UTF_16LE
		);

		UnmanagedMemory.free(location);
		return folderPath;
	}

	@CFunction(transition = CFunction.Transition.TO_NATIVE)
	private static native int SHGetKnownFolderPath(PointerBase rfid, int dwFlags,
		VoidPointer hToken, CCharPointerPointer ppszPath
	);
}
