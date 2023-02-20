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
import java.util.List;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.WordFactory;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUID;
import org.unigrid.hedgehog.nativeimage.windows.Ole32Wrapper.Header;

@CLibrary("ole32")
@CContext(Header.class)
@Platforms(Platform.WINDOWS.class)
public class Ole32Wrapper {
	private static final int TEMPORARY_GUID_ALLOCATION_SIZE = 128;

	public static class Header implements CContext.Directives {
		@Override
		public List<String> getHeaderFiles() {
			return List.of("<windows.h>", "<combaseapi.h>");
		}
	}

	public static GUID getFolder(KnownFolders.GUIDHolder holder) throws WindowsException {
		final CCharPointer guidString = UnmanagedMemory.calloc(TEMPORARY_GUID_ALLOCATION_SIZE);

		CTypeConversion.toCString(holder.getIdentifier(), StandardCharsets.UTF_16LE,
			guidString, WordFactory.unsigned(TEMPORARY_GUID_ALLOCATION_SIZE)
		);

		final int status = Ole32Wrapper.IIDFromString(guidString, holder.getGuid());

		if (status != 0) {
			throw new WindowsException(String.format("Failed to find GUID for %s",
				holder.getIdentifier()), status
			);
		}

		return holder.getGuid();
	}

	@CFunction(transition = CFunction.Transition.NO_TRANSITION)
	private static native int IIDFromString(CCharPointer lpsz, GUID lpiid);
}
