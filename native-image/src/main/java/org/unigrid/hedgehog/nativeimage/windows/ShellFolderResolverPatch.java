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
import java.util.Objects;
import net.harawata.appdirs.AppDirsException;
import net.harawata.appdirs.impl.ShellFolderResolver;
import net.harawata.appdirs.impl.WindowsAppDirs.FolderId;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUID;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUIDHolder;

@TargetClass(ShellFolderResolver.class)
public final class ShellFolderResolverPatch {
	@Substitute
	public String resolveFolder(FolderId folderId) {
		final String identifier = KnownFolders.GUID_MAPPINGS.get(folderId);

		if (Objects.isNull(identifier)) {
			throw new AppDirsException("Unmapped folder ID " + folderId + " was specified.");
		}

		try (GUIDHolder holder = new GUIDHolder(identifier)) {
			final GUID guid = Ole32Wrapper.getFolder(holder);
			return Shell32Wrapper.getKnownFolderPath(guid);
		} catch (WindowsException ex) {
			throw new AppDirsException(ex.getMessage());
		}
	}

	@Substitute
	protected int convertFolderIdToCsidl(FolderId folderId) {
		throw new IllegalStateException("No longer supported.");
	}
}
