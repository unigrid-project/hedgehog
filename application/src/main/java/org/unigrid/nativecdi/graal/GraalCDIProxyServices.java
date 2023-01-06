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
import org.graalvm.nativeimage.ImageInfo;
import org.unigrid.nativecdi.Application;

public class GraalCDIProxyServices /*extends WeldDefaultProxyServices*/ {
	/*private final List<WeldProxyConfig> weldProxyConfigs;
	private final String contextId;

	public GraalCDIProxyServices(String contextId) {
		super();
		System.out.println(new GraalProxyObject());

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

		if (ImageInfo.inImageCode()) {
			throw new IllegalStateException("Can't dynamically define classes at runtime under native mode");
		}

		return super.defineClass(originalClass, className, classBytes, off, len, protectionDomain);
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

		if (originalClass.equals(Application.class)) {
			return GraalProxyObject.class;
		} else {
			try {
				return super.loadClass(originalClass, classBinaryName);
			} catch (ClassNotFoundException ex) {
				ex.printStackTrace();
				System.out.println("SHIT!!");
			}
		}
		return originalClass;

		//return null;
	}

	@Override
	public void cleanup() {
		System.out.println("cleanup!");
		super.cleanup();
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
	}*/
}
