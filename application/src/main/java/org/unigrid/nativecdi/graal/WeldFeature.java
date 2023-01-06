package org.unigrid.nativecdi.graal;

/*
 * Copyright (c) 2019, 2022 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.jdk.Resources;
import com.oracle.svm.core.jdk.ResourcesHelper;
import com.oracle.svm.hosted.reflect.proxy.ProxyRegistry;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.CDI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.graalvm.nativeimage.RuntimeOptions;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.Feature.DuringSetupAccess;
import org.graalvm.nativeimage.hosted.Feature.IsInConfigurationAccess;
import org.graalvm.nativeimage.hosted.RuntimeProxyCreation;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import org.jboss.weld.events.WeldEvent;
/*import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.bean.proxy.ClientProxyFactory;
import org.jboss.weld.bean.proxy.ClientProxyProvider;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.event.EventImpl;
import org.jboss.weld.event.ObserverNotifier;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.manager.api.WeldManager;
import org.jboss.weld.util.Proxies;*/
import org.unigrid.nativecdi.Application;
import org.unigrid.nativecdi.Main;

@AutomaticFeature
public class WeldFeature implements Feature {
	@Override
	public boolean isInConfiguration(IsInConfigurationAccess access) {
		return true;
	}

	@Override
	@SneakyThrows
	public void duringSetup(DuringSetupAccess access) {
		//Resources.registerResource();
		//final SeContainerInitializer initializer = SeContainerInitializer.newInstance();

		/*((Weld) initializer).containerId("hedgehog-main")
			.addServices(new GraalCDIProxyServices("hedgehog-main")
		);*/
		/*try (SeContainer container = initializer.disableDiscovery()
			.addPackages(Main.class)
			.addBeanClasses(Application.class).initialize()) {
			container.select(Application.class).get().start();
		}*/
	}
}

//RuntimeReflection.register(BeanManagerImpl.class);

/*try {
		//System.out.println(BeanManagerProxy.unwrap(BeanManagerImpl.newRootManager("", "", null)));
		//System.out.println(new BeanManagerImpl(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
		} catch(Exception ex) {
			System.out.println(ex);
			ex.printStackTrace();
		}

		Class<?> beanManagerClass = access.findClassByName("org.jboss.weld.manager.BeanManagerImpl");
		Set<BeanId> processed = new HashSet<>();
		Set<Set<Type>> processedExplicitProxy = new HashSet<>();
		Set<Object> processedBeanManagers = Collections.newSetFromMap(new IdentityHashMap<>());
		List<WeldProxyConfig> weldProxyConfigs = weldProxyConfigurations(access);

		trace(() -> "Weld feature");

		access.registerObjectReplacer((obj) -> {
			if (beanManagerClass.isInstance(obj) && processedBeanManagers.add(obj)) {
				try {
					BeanManagerImpl bm = (BeanManagerImpl) obj;
					ClientProxyProvider cpp = bm.getClientProxyProvider();

					String contextId = bm.getContextId();
					List<Bean<?>> beans = bm.getBeans();

					iterateBeans(bm, cpp, processed, beans);

					weldProxyConfigs.forEach(proxy -> {
						initializeProxy(access,
							processedExplicitProxy,
							contextId,
							// bean class is the class defining the beans (such as bean producer, or the bean type
							// itself if this is a managed bean - used to generate name of the client proxy class
							proxy.beanClass,
							// actual types of the bean - used to generate the client proxy class
							proxy.interfaces);
					});
				} catch (Exception ex) {
					warn(() -> "Error processing object " + obj);
					warn(() -> "  " + ex.getClass().getName() + ": " + ex.getMessage());
				}
			}
			return obj;
		});
	}

	private void trace(Supplier<String> message) {
		System.out.println(message.get());
	}

	private void warn(Supplier<String> message) {

		System.err.println(message.get());
	}

	private void initializeProxy(DuringSetupAccess access,
		Set<Set<Type>> processedExplicitProxy,
		String contextId,
		String beanClassName,
		String... typeClasses) {

		trace(() -> beanClassName);

		Class<?> beanClass = access.findClassByName(beanClassName);
		if (null == beanClass) {
			warn(() -> "  Bean class not found: " + beanClassName);
			return;
		}

		Set<Type> types = new HashSet<>();

		for (String typeClass : typeClasses) {
			Class<?> theClass = access.findClassByName(typeClass);
			if (null == theClass) {
				warn(() -> "  Type class not found: " + typeClass);
				return;
			}
			types.add(theClass);
		}

		if (processedExplicitProxy.add(types)) {
			Bean<?> theBean = new ProxyBean(beanClass, types);

			Proxies.TypeInfo typeInfo = Proxies.TypeInfo.of(types);

			ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);

			Class<?> proxyClass = cpf.getProxyClass();

			trace(() -> "  Registering proxy class " + proxyClass.getName() + " with types " + types);
			RuntimeReflection.register(proxyClass);
			RuntimeReflection.register(proxyClass.getConstructors());
			RuntimeReflection.register(proxyClass.getDeclaredConstructors());
			RuntimeReflection.register(proxyClass.getMethods());
			RuntimeReflection.register(proxyClass.getDeclaredMethods());
			RuntimeReflection.register(proxyClass.getFields());
			RuntimeReflection.register(proxyClass.getDeclaredFields());
		}
	}

	private void iterateBeans(BeanManagerImpl bm,
		ClientProxyProvider cpp,
		Set<BeanId> processed,
		Collection<Bean<?>> beans) {
		for (Bean<?> bean : beans) {
			Set<Type> beanTypes = bean.getTypes();

			BeanId id = new BeanId(bean.getBeanClass(), beanTypes);

			// the id is a combination of bean class and bean types, we missed types before (when using bean class only)
			if (!processed.add(id)) {
				continue;
			}

			try {
				Object proxy = cpp.getClientProxy(bean);
				trace(() -> "Created proxy for bean class: "
					+ bean.getBeanClass().getName()
					+ ", bean type: "
					+ beanTypes
					+ ", proxy class: "
					+ proxy.getClass().getName());
			} catch (Throwable e) {
				// try interfaces
				warn(() -> "Failed to create a proxy for bean "
					+ bean.getBeanClass() + ", "
					+ e.getClass().getName() + ": "
					+ e.getMessage() + " - this bean will not work in native-image");
			}

			// now we also need to handle all types
			beanTypes.forEach(type -> iterateBeans(bm, cpp, processed, bm.getBeans(type)));
		}
	}

	static List<WeldProxyConfig> weldProxyConfigurations(DuringSetupAccess access) {
		try {
			final URL resource = WeldFeature.class.getClassLoader()
				.getResource("META-INF/native-image/weld-proxies.json");

			System.out.println(resource);

			final ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			return (List) Arrays.asList(mapper.readValue(resource, GraalCDIProxyServices.WeldProxyConfig[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Failed to get resources", e);
		}
	}

	@Data
	public static class WeldProxyConfig {
		private String beanClass;
		private String[] interfaces;
	}

	private static final class BeanId {
		private final Class<?> beanClass;
		private final Set<Type> types;

		private BeanId(Class<?> beanClass, Set<Type> types) {
			this.beanClass = beanClass;
			this.types = types;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			BeanId beanId = (BeanId) o;
			return beanClass.equals(beanId.beanClass)
				&& types.equals(beanId.types);
		}

		@Override
		public int hashCode() {
			return Objects.hash(beanClass, types);
		}

		@Override
		public String toString() {
			return beanClass.getName() + ": " + types;
		}*/
//}
//@AutomaticFeature
//public class WeldFeature implements Feature {
//	@Override
//	@SneakyThrows
//	public void beforeAnalysis(BeforeAnalysisAccess access) {
//		final SeContainerInitializer initializer = SeContainerInitializer.newInstance();

/*((Weld) initializer).containerId("hedgehog-main")
			.addServices(new GraalCDIProxyServices("hedgehog-main")
		);*/
//		try (SeContainer container = initializer.disableDiscovery()
//			.addPackages(Main.class)
//			.addBeanClasses(Application.class).initialize()) {
//			container.select(Application.class).get().start();
//		}
//final SeContainer container = Weld.newInstance().initialize();
//final SeContainerInitializer initializer = SeContainerInitializer.newInstance();
//initializer.initialize();
/*try ( SeContainer container = initializer.disableDiscovery()
			.addPackages(Main.class)
			.addBeanClasses(Application.class).initialize()) {

			container.select(Application.class).get().start();
		}*/
//RuntimeReflection.register(ObserverNotifier.class);
//System.out.println(container.select(Application.class).get());
/*access.registerAsUsed(org.jboss.weld.manager.api.WeldManager.class);
		access.registerAsUsed(org.jboss.weld.manager.BeanManagerImpl.class);
		RuntimeReflection.register(WeldManager.class);*/
//}

/*@Override
	public void duringSetup(DuringSetupAccess access) {
		
		trace(() -> "Weld feature starting up");
		final Class<?> beanManagerClass = access.findClassByName("org.jboss.weld.manager.api.WeldManager");
		//Set<BeanId> processed = new HashSet<>();
		Set<Set<Type>> processedExplicitProxy = new HashSet<>();
		//List<WeldProxyConfig> weldProxyConfigs = weldProxyConfigurations();

		trace(() -> "Weld feature about to iterate: " + beanManagerClass);

		//SeContainer container = SeContainerInitializer.newInstance().initialize();
		//System.out.println("xoxo " + ((WeldManager) container.getBeanManager()).getId());
		//System.out.println("xoxo " + ((WeldContainer) container).getId());

		access.registerObjectReplacer((obj) -> {
			if (obj.getClass().getName().contains("BeanManagerImpl")) {
				trace(() -> "found: " + obj.getClass().getName());
			}

			/*if (beanManagerClass.isInstance(obj)) {
				trace(() -> "replacer: " + obj);

				try {
					BeanManagerImpl bm = (BeanManagerImpl) obj;
					ClientProxyProvider cpp = bm.getClientProxyProvider();

					String contextId = bm.getContextId();
					List<Bean<?>> beans = bm.getBeans();

					iterateBeans(bm, cpp, beans);

					weldProxyConfigs.forEach(proxy -> {
						initializeProxy(access,
							processedExplicitProxy,
							contextId,
							// bean class is the class defining the beans (such as bean producer, or the bean type
							// itself if this is a managed bean - used to generate name of the client proxy class
							proxy.beanClass,
							// actual types of the bean - used to generate the client proxy class
							proxy.interfaces);
					});
				} catch (Exception ex) {
					warn(() -> "Error processing object " + obj);
					warn(() -> "  " + ex.getClass().getName() + ": " + ex.getMessage());
				}
			}
			return obj;
		});
	}*/

 /*private void trace(Supplier<String> message) {
		System.out.println(message.get());
	}

	private void warn(Supplier<String> message) {
		System.err.println(message.get());
	}

	private void initializeProxy(DuringSetupAccess access, Set<Set<Type>> processedExplicitProxy, String contextId,
		String beanClassName, String... typeClasses) {

		trace(() -> beanClassName);
		Class<?> beanClass = access.findClassByName(beanClassName);

		if (null == beanClass) {
			warn(() -> "  Bean class not found: " + beanClassName);
			return;
		}

		Set<Type> types = new HashSet<>();

		for (String typeClass : typeClasses) {
			Class<?> theClass = access.findClassByName(typeClass);
			if (null == theClass) {
				warn(() -> "  Type class not found: " + typeClass);
				return;
			}
			types.add(theClass);
		}

		if (processedExplicitProxy.add(types)) {
			Bean<?> theBean = new ProxyBean(beanClass, types);
			Proxies.TypeInfo typeInfo = Proxies.TypeInfo.of(types);
			ClientProxyFactory<?> cpf = new ClientProxyFactory<>(contextId, typeInfo.getSuperClass(), types, theBean);
			Class<?> proxyClass = cpf.getProxyClass();

			trace(() -> "  Registering proxy class " + proxyClass.getName() + " with types " + types);

			RuntimeReflection.register(proxyClass);
			RuntimeReflection.register(proxyClass.getConstructors());
			RuntimeReflection.register(proxyClass.getDeclaredConstructors());
			RuntimeReflection.register(proxyClass.getMethods());
			RuntimeReflection.register(proxyClass.getDeclaredMethods());
			RuntimeReflection.register(proxyClass.getFields());
			RuntimeReflection.register(proxyClass.getDeclaredFields());
		}
	}

	private void iterateBeans(BeanManagerImpl bm, ClientProxyProvider cpp, Collection<Bean<?>> beans) {
		for (Bean<?> bean : beans) {
			Set<Type> beanTypes = bean.getTypes();

			try {
				Object proxy = cpp.getClientProxy(bean);
				trace(() -> "Created proxy for bean class: "
					+ bean.getBeanClass().getName()
					+ ", bean type: "
					+ beanTypes
					+ ", proxy class: "
					+ proxy.getClass().getName());
			} catch (Throwable e) {
				// try interfaces
				warn(() -> "Failed to create a proxy for bean "
					+ bean.getBeanClass() + ", "
					+ e.getClass().getName() + ": "
					+ e.getMessage() + " - this bean will not work in native-image");
			}

			// now we also need to handle all types
			beanTypes.forEach(type -> iterateBeans(bm, cpp, bm.getBeans(type)));
		}
	}*/

 /*static List<WeldProxyConfig> weldProxyConfigurations() {
		try {
			URL resource = WeldFeature.class.getClassLoader().getResource("META-INF/native-image/weld-proxies.json");
			final ObjectMapper mapper = new ObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
			return Arrays.asList(mapper.readValue(resource, WeldProxyConfig[].class));

		} catch (IOException e) {
			throw new IllegalStateException("Failed to get resources", e);
		}
	}*/

 /*@Data
	public static class WeldProxyConfig {
		private String beanClass;
		private String[] interfaces;
	}*/
//	@Override
//	public boolean isInConfiguration(IsInConfigurationAccess access) {
//		return true;
//	}
//}
