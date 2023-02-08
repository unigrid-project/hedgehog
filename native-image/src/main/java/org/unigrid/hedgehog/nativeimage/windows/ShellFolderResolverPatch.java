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

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.sun.jna.platform.win32.Guid.GUID;
import net.harawata.appdirs.AppDirsException;
import net.harawata.appdirs.impl.ShellFolderResolver;
import net.harawata.appdirs.impl.WindowsAppDirs.FolderId;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.APPDATA;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.COMMON_APPDATA;
import static net.harawata.appdirs.impl.WindowsAppDirs.FolderId.LOCAL_APPDATA;
import org.graalvm.nativeimage.c.type.VoidPointer;

@TargetClass(ShellFolderResolver.class)
public final class ShellFolderResolverPatch {
	public static class ConvertFolderId {
		private static VoidPointer toVoidPointer(FolderId folderId) {
			System.out.println("SHEEEEEEEEEEEEEEEEEEEEEELL2");
			switch (folderId) {
				case APPDATA:
					System.out.println("SHEEEEEEEEEEEEEEEEEEEEEELL3");
					return KnownFolders.folderRoamingAppData();
				case LOCAL_APPDATA:
					System.out.println("SHEEEEEEEEEEEEEEEEEEEEEELL4");
					return KnownFolders.folderLocalAppData();
				case COMMON_APPDATA:
					System.out.println("SHEEEEEEEEEEEEEEEEEEEEEELL5");
					return KnownFolders.folderProgramData();
				default:
					throw new AppDirsException("Unknown folder ID " + folderId + " was specified.");
			}
		}
	}

	@Substitute
	public String resolveFolder(FolderId folderId) {
		try {
			System.out.println("SHEEEEEEEEEEEEEEEEEEEEEELL1");
			return Shell32Wrapper.GetKnownFolderPath(ConvertFolderId.toVoidPointer(folderId));
		} catch (WindowsException ex) {
			throw new AppDirsException("SHGetKnownFolderPath() returns an error: " + ex.getErrorCode());
		}
	}

	@Substitute
	private GUID convertFolderIdToGuid(FolderId folderId) {
		throw new IllegalStateException("No longer supported.");
	}

	@Substitute
	protected int convertFolderIdToCsidl(FolderId folderId) {
		throw new IllegalStateException("No longer supported.");
	}
}
