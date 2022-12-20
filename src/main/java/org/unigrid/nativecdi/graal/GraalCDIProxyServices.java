package org.unigrid.nativecdi.graal;

import java.security.ProtectionDomain;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.jboss.weld.serialization.spi.ProxyServices;

public class GraalCDIProxyServices implements ProxyServices  {
		@Override
		public Class<?> defineClass(Class<?> originalClass, String className, byte[] classBytes, int off, int len, ProtectionDomain protectionDomain) throws ClassFormatError {
			System.out.println("originalClass: " + originalClass);
			System.out.println("classNamne: " + className);
			System.out.println("offset: " + off);
			System.out.println("len: " + len);

			return ProxyServices.super.defineClass(originalClass, className, classBytes, off, len, protectionDomain); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/OverriddenMethodBody
		}

		@Override
		public Class<?> loadClass(Class<?> originalClass, String classBinaryName) throws ClassNotFoundException {
			System.out.println("x originalClass: " + originalClass);
			System.out.println("x classNamne: " + classBinaryName);
			//RuntimeProxyCreation.
			return originalClass;
			//return ProxyServices.super.loadClass(originalClass, classBinaryName); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/OverriddenMethodBody
		}

		@Override
		public void cleanup() {
			System.out.println("cleanup!");
		}
}
