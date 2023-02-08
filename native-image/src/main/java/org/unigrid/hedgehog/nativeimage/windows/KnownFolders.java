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

import java.util.List;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.word.PointerBase;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.Header;

@CContext(Header.class)
public class KnownFolders {
	static class Header implements CContext.Directives {
		@Override
		public List<String> getHeaderFiles() {
			return List.of("<basetyps.h>", "<initguid.h>", "<knownfolders.h>");
		}
	}

	@CConstant("&FOLDERID_LocalAppData")
	public static native PointerBase folderLocalAppData();

	@CConstant("&FOLDERID_ProgramData")
	public static native PointerBase folderProgramData();

	@CConstant("&FOLDERID_RoamingAppData")
	public static native PointerBase folderRoamingAppData();
}
