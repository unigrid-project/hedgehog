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

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUID;

@CLibrary("shell32")
@CContext(KnownFolders.Header.class)
public class Shell32Wrapper {
	@CFunction(transition = Transition.TO_NATIVE)
	public static native int SHGetKnownFolderPath(GUID rfid, int dwFlags, VoidPointer hToken, CCharPointer ppszPath);

	public static String GetKnownFolderPath(GUID guid) throws WindowsException {
		final byte[] path = new byte[260 /* MAX_PATH */];

		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST0");

		System.out.println("[KF1]: " + guid.rawValue());
		System.out.println("[KF2]: " + guid.getData1());
		//System.out.println("[KF2]: " + guid.read().getData1());
		//System.out.println("[KF3]: " + guid.read().getData2());
		//System.out.println("[KF2]: " + KnownFolders.folderProgramData().getData2());
		//System.out.println("[KF3]: " + KnownFolders.folderProgramData().getData3());
		//System.out.println("[KF4]: " + KnownFolders.folderProgramData().getData4());

		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST1");
		final int result = SHGetKnownFolderPath(guid, 0, WordFactory.nullPointer(), CTypeConversion.toCBytes(path).get());
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST2");
		if (result != 0) {
			System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST3");
			throw new WindowsException(result);
		}
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST4");
		return String.valueOf(path);
	}
}
