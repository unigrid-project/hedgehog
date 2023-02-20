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

import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.harawata.appdirs.impl.WindowsAppDirs;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.APPDATA;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.COMMON_APPDATA;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.LOCAL_APPDATA;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.word.PointerBase;

@Platforms(Platform.WINDOWS.class)
@CContext(Shell32Wrapper.Header.class)
public class KnownFolders {
	public static final Map<WindowsAppDirs.FolderId, String> GUID_MAPPINGS = Map.of(
		APPDATA, "{3EB685DB-65F9-4CF6-A03A-E3EF65729F3D}",
		LOCAL_APPDATA, "{F1B32785-6FBA-4FCF-9D55-7B8E7F157091}",
		COMMON_APPDATA, "{62AB5D82-FDC1-4DC3-A9DD-070D1D495D97}"
	);

	@CStruct(value = "GUID", isIncomplete = true)
	public interface GUID extends PointerBase {
		/* We don't really care about the content, so lets leave it empty! */
	}

	@RequiredArgsConstructor
	public static class GUIDHolder implements AutoCloseable {
		private static final int SIZE = 32;
		@Getter private final GUID guid = UnmanagedMemory.calloc(SIZE);
		@Getter private final String identifier;

		@Override
		public void close() {
			UnmanagedMemory.free(guid);
		}
	}
}
