package org.unigrid.nativecdi.graal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.spi.Bean;
import java.io.IOException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.jboss.weld.bean.proxy.ClientProxyFactory;
import org.jboss.weld.bean.proxy.MethodHandler;
import org.jboss.weld.bean.proxy.ProxyFactory;
import org.jboss.weld.bean.proxy.ProxyObject;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.util.Proxies;

public class GraalCDIProxyServices implements ProxyServices {
	private final List<WeldProxyConfig> weldProxyConfigs;
	private final String contextId;

	public GraalCDIProxyServices(String contextId) {
		try {
			final URL resource = WeldFeature.class.getClassLoader()
				.getResource("META-INF/native-image/weld-proxies.json");

			final ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			weldProxyConfigs = Arrays.asList(mapper.readValue(resource, WeldProxyConfig[].class));
			this.contextId = contextId;

		} catch (IOException ex) {
			throw new IllegalStateException("Failed to get resources", ex);
		}
	}

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
		//ProxyObject p = new ProxyObject();

		/*final Bean<?> bean = new ProxyBean(beanClass, types);
		Proxies.TypeInfo typeInfo = Proxies.TypeInfo.of(types);
		ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);
		Class<?> proxyClass = cpf.getProxyClass();

		ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);*/
		return originalClass;
		//return ProxyServices.super.loadClass(originalClass, classBinaryName); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/OverriddenMethodBody
	}

	@Override
	public void cleanup() {
		System.out.println("cleanup!");
	}

	public static class GraalProxyObject implements ProxyObject {
		@Override
		public void weld_setHandler(MethodHandler mh) {
			//new ClientProxyFactory<?>();
			throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
		}

		@Override
		public MethodHandler weld_getHandler() {
			throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
		}

	}

	@Data
	public static class WeldProxyConfig {
		private String beanClass;
		private String[] interfaces;
	}

}
