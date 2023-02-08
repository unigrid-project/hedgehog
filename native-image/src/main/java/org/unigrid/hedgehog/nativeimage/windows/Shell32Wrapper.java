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

import lombok.Cleanup;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CFunction.Transition;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.VoidPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;

@CLibrary("shell32")
public class Shell32Wrapper {
	@CFunction(transition = Transition.NO_TRANSITION)
	public static native int SHGetKnownFolderPath(VoidPointer rfid, int dwFlags, VoidPointer hToken, CCharPointer ppszPath);

	public static String GetKnownFolderPath(VoidPointer ptr) throws WindowsException {
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST1");
		@Cleanup final CTypeConversion.CCharPointerHolder path = CTypeConversion.toCBytes(new byte[260 /* MAX_PATH */]);
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST2");
		final int result = SHGetKnownFolderPath(ptr, 0, WordFactory.nullPointer(), path.get());
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST3");
		if (result != 0) {
			System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST4");
			throw new WindowsException(result);
		}
		System.out.println("TEEEEEEEEEEEEEEEEEEEEEEEEEEST5");
		return CTypeConversion.toJavaString(path.get());
	}
}
