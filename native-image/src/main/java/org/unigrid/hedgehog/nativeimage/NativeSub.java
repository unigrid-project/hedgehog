/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.nativeimage;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

//@TargetClass(com.sun.jna.Native.class)
public final class NativeSub {
	
	//@Alias
	static String jnidispatchPath;
	
	//@Substitute
	public static long findSymbol(long handel, String name) {
		System.out.println("input " + name);
		System.out.println("jniDispatch" + jnidispatchPath);
		return handel;
	}
	
	/*@Substitute
	public static boolean getBoolean(String name) {
		return true;
	}*/
}
