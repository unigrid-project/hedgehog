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

import java.util.Arrays;
import java.util.List;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CPointerTo;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.Header;

@CLibrary("shell32")
@CContext(Header.class)
public class KnownFolders {
	static class Header implements CContext.Directives {
		@Override
		public List<String> getHeaderFiles() {
			return List.of("<windows.h>", "<knownfolders.h>", "<shlobj.h>");
		}

		@Override
		public List<String> getOptions() {
			return Arrays.asList(new String[]{
				"-Iff_headers", "-std=c++11 -DINITGUID"
			});
		}

		@Override
		public List<String> getLibraries() {
			return Arrays.asList("shell32");
		}
	}

	@CStruct("GUID")
	public interface GUID extends PointerBase {
		@CField("Data1") int getData1();
		@CField("Data1") void setData1(int data);
		@CField("Data2") short getData2();
		@CField("Data2") void setData2(short data);
		@CField("Data3") short getData3();
		@CField("Data3") void setData3(short data);
		@CField("Data4") CCharPointer data4();
	}

	@CStruct("GUID")
	public interface GUID2 extends PointerBase {
		@CField("Data1") int getData1();
		@CField("Data1") void setData1(int data);
		@CField("Data2") short getData2();
		@CField("Data2") void setData2(short data);
		@CField("Data3") short getData3();
		@CField("Data3") void setData3(short data);
		@CField("Data4") CCharPointer data4();
	}

	@CPointerTo(GUID2.class)
	public interface GUIDPointer extends PointerBase {
		GUID2 read();
		void write(GUID2 guid);
	}

	/* {F1B32785-6FBA-4FCF-9D55-7B8E7F157091} */
	public static GUID folderLocalAppData() {
		final GUID guid = StackValue.get(GUID.class);

		guid.setData1(0xf1b32785);
		guid.setData2((short) 0x6fba);
		guid.setData3((short) 0x4fcf);

		guid.data4().addressOf(0).write((byte) 0x9d);
		guid.data4().addressOf(1).write((byte) 0x55);
		guid.data4().addressOf(2).write((byte) 0x7b);
		guid.data4().addressOf(3).write((byte) 0x8e);
		guid.data4().addressOf(4).write((byte) 0x7f);
		guid.data4().addressOf(5).write((byte) 0x15);
		guid.data4().addressOf(6).write((byte) 0x70);
		guid.data4().addressOf(7).write((byte) 0x91);

		return guid;
	}

	/* {62AB5D82-FDC1-4DC3-A9DD-070D1D495D97} */
	public static GUID folderProgramData() {
		final GUID guid = StackValue.get(GUID.class);

		guid.setData1(0x62ab5d82);
		guid.setData2((short) 0xfdc1);
		guid.setData3((short) 0x4dc3);

		guid.data4().addressOf(0).write((byte) 0xa9);
		guid.data4().addressOf(1).write((byte) 0xdd);
		guid.data4().addressOf(2).write((byte) 0x07);
		guid.data4().addressOf(3).write((byte) 0x0d);
		guid.data4().addressOf(4).write((byte) 0x1d);
		guid.data4().addressOf(5).write((byte) 0x49);
		guid.data4().addressOf(6).write((byte) 0x5d);
		guid.data4().addressOf(7).write((byte) 0x97);

		return guid;
	}

	@CConstant("&FOLDERID_RoamingAppData")
	public static native GUIDPointer folderRoamingAppData2();

	/* {3EB685DB-65F9-4CF6-A03A-E3EF65729F3D} */
	public static GUID folderRoamingAppData() {
		System.out.println("XX0");
		System.out.println(folderRoamingAppData2().rawValue());
		System.out.println("XX0.1");
		System.out.println(folderRoamingAppData2().read().getData1());
		System.out.println("XX1");
		final GUID guid = StackValue.get(GUID.class);
		System.out.println("XX2");
		guid.setData1(0x3eb685db);
		System.out.println("XX3");
		guid.setData2((short) 0x65f9);
		guid.setData3((short) 0x4cf6);
		System.out.println("XX4");
		System.out.println(guid.data4().rawValue());
		System.out.println("XX4.1");
		System.out.println(guid.data4().addressOf(0).rawValue());
		System.out.println("XX4.2");
		System.out.println(guid.data4().addressOf(1).rawValue());
		System.out.println("XX4.3");
		System.out.println(guid.data4().addressOf(2).rawValue());

		guid.data4().addressOf(0).write((byte) 0xa0);
		System.out.println("XX5");
		guid.data4().addressOf(1).write((byte) 0x3a);
		System.out.println("XX6");
		guid.data4().addressOf(2).write((byte) 0xe3);
		System.out.println("XX7");
		guid.data4().addressOf(3).write((byte) 0xef);
		guid.data4().addressOf(4).write((byte) 0x65);
		guid.data4().addressOf(5).write((byte) 0x72);
		guid.data4().addressOf(6).write((byte) 0x9f);
		guid.data4().addressOf(7).write((byte) 0x3d);
		System.out.println("XX8");

		return guid;
	}
}
