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
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.jboss.weld.bean.proxy.ClientProxyFactory;
import org.jboss.weld.bean.proxy.MethodHandler;
import org.jboss.weld.bean.proxy.ProxyMethodHandler;
import org.jboss.weld.bean.proxy.ProxyObject;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.util.Proxies;

public class GraalCDIProxyServices implements ProxyServices {
	private final List<WeldProxyConfig> weldProxyConfigs;
	private final String contextId;

	public GraalCDIProxyServices(String contextId) {
		System.out.println("context: " + contextId);
		this.contextId = contextId;

		try {
			final URL resource = WeldFeature.class.getClassLoader()
				.getResource("META-INF/native-image/weld-proxies.json");

			System.out.println(resource);

			final ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			weldProxyConfigs = Arrays.asList(mapper.readValue(resource, WeldProxyConfig[].class));

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
		System.out.println("bytes: " + classBytes);

		return ProxyServices.super.defineClass(originalClass, className, classBytes, off, len, protectionDomain);
	}

	@Override
	public Class<?> loadClass(Class<?> originalClass, String classBinaryName) throws ClassNotFoundException {
		System.out.println("x originalClass: " + originalClass);
		System.out.println("x classNamne: " + classBinaryName);

		//RuntimeProxyCreation.
		//final GraalProxyObject proxy = new GraalProxyObject();		

		//final Bean<?> bean = new ProxyBean(beanClass, types);
		//Proxies.TypeInfo typeInfo = Proxies.TypeInfo.of(types);
		//ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);
		//Class<?> proxyClass = cpf.getProxyClass();

		//ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);

		//return GraalProxyObject.class;

		return GraalCDIProxyServices.class.getClassLoader().loadClass(classBinaryName);
	}

	@Override
	public void cleanup() {
		System.out.println("cleanup!");
	}

	public static class GraalProxyObject implements ProxyObject {
		private MethodHandler handler;


		@Override
		public void weld_setHandler(MethodHandler mh) {
			final ProxyMethodHandler pme = (ProxyMethodHandler) mh;
			System.out.println("a:" + pme.getBean());
			System.out.println("a:" + pme.getContextualInstance());
			System.out.println("a:" + pme.getInstance());
			handler = mh;
			//new ClientProxyFactory<?>();
			//throw new UnsupportedOperationException("Not supported yet.");
		}

		@Override
		public MethodHandler weld_getHandler() {
			//throw new UnsupportedOperationException("Not supported yet.");
			return handler;
		}

	}

	@Data
	public static class WeldProxyConfig {
		private String beanClass;
		private String[] interfaces;
	}

}
