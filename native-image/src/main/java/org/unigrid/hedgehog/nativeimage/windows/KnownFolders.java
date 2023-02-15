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

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.util.encoders.Hex;
import org.graalvm.nativeimage.PinnedObject;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.constant.CConstant;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CPointerTo;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.struct.SizeOf;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.Header;

@CContext(Header.class)
public class KnownFolders {
	public static class Header implements CContext.Directives {
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

	@CStruct(value = "GUID", isIncomplete = true)
	public interface GUID extends PointerBase {
		@CField("Data1") int getData1();
		@CField("Data1") void setData1(int data);
		@CField("Data2") short getData2();
		@CField("Data2") void setData2(short data);
		@CField("Data3") short getData3();
		@CField("Data3") void setData3(short data);
		@CField("Data4") CCharPointer data4();
	}

	/*@CPointerTo(GUID.class)
	public interface GUIDPointer extends PointerBase {
		GUID read();
		void write(GUID guid);
	}*/

	public static final String FOLDERID_LOCAL_APP_DATA = "{F1B32785-6FBA-4FCF-9D55-7B8E7F157091}";
	public static final String FOLDERID_PROGRAM_DATA = "{62AB5D82-FDC1-4DC3-A9DD-070D1D495D97}";
	public static final String FOLDERID_ROAMING_APP_DATA = "{3EB685DB-65F9-4CF6-A03A-E3EF65729F3D}";

	public static final String FOLDERID_LOCAL_APP_DATA_HEX = "F1B327856FBA4FCF9D557B8E7F157091";
	public static final String FOLDERID_PROGRAM_DATA_HEX = "62AB5D82FDC14DC3A9DD070D1D495D97";
	public static final String FOLDERID_ROAMING_APP_DATA_HEX = "3EB685DB65F94CF6A03AE3EF65729F3D";

	public static GUID getFolder(String folder) {
		//final GUID guid = StackValue.get(32); 
		//final GUID guid = PinnedObject.create(folder)

		final GUID guid = UnmanagedMemory.calloc(32);
		final CCharPointer guidString = UnmanagedMemory.calloc(256);

		System.out.println("folder: " + folder);
		System.out.println("predata1: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData1())));
		System.out.println("predata2: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData2())));
		System.out.println("predata3: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData3())));

		int i = 0;
		
		final UnsignedWord count = CTypeConversion.toCString(folder, StandardCharsets.UTF_16LE, guidString, WordFactory.unsigned(256));
		System.out.println("count: " + count.rawValue());
		System.out.println("native[0]: " + guidString.read(0));
		System.out.println("native[10]: " + guidString.read(10));
		System.out.println("native[30]: " + guidString.read(10));
		System.out.println("native[37]: " + guidString.read(37));
		System.out.println("native[38]: " + guidString.read(38));
		System.out.println("native[70]: " + guidString.read(70));
		System.out.println("native[120]: " + guidString.read(120));
		i = Ole32Wrapper.IIDFromString(guidString, guid);
		System.out.println("return: " + i);

		System.out.println("data1: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData1())));
		System.out.println("data2: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData2())));
		System.out.println("data3: " + HexFormat.fromHexDigits(Integer.toHexString(guid.getData3())));
		return guid;
	}
}
