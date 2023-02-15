package org.unigrid.hedgehog.nativeimage.windows;

import java.util.Arrays;
import java.util.List;
import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CLibrary;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CShortPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion.CCharPointerHolder;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUID;
//import org.unigrid.hedgehog.nativeimage.windows.KnownFolders.GUIDPointer;

@CLibrary("ole32")
@CContext(KnownFolders.Header.class)
public class Ole32Wrapper {
	public static class Header implements CContext.Directives {
		@Override
		public List<String> getHeaderFiles() {
			return List.of("<windows.h>", "<combaseapi.h>");
		}

		@Override
		public List<String> getOptions() {
			return Arrays.asList(new String[]{
				"-Iff_headers", "-std=c++11 -DINITGUID"
			});
		}

		@Override
		public List<String> getLibraries() {
			return Arrays.asList("ole32");
		}
	}

	@CFunction(transition = CFunction.Transition.NO_TRANSITION)
	public static native int IIDFromString(CCharPointer lpsz, GUID lpiid);

}
