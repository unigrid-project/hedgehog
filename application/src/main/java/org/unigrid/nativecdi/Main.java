package org.unigrid.nativecdi;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.PassivationCapable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.bean.builtin.ExtensionBean;
import org.jboss.weld.bean.proxy.ProxyFactory;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.manager.BeanManagerImpl;
import org.unigrid.nativecdi.graal.GraalCDIProxyServices;
import org.unigrid.nativecdi.graal.BuildExtension;

public class Main {
	static {
		try {
		//System.out.println(BeanManagerProxy.unwrap(BeanManagerImpl.newRootManager("", "", null)));
		//System.out.println(new BeanManagerImpl(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));
		} catch(Exception ex) {
			
		}
	}
	public static void main(String[] args) throws Exception {
		System.out.println(System.getProperty("deployment.user.cachedir"));

		//ServiceLoader<Application> loader = ServiceLoader.load(Application.class);
		//Iterator<Application> iterator = loader.iterator();
		//if (iterator.hasNext()) {
		//	System.out.println("Service found");
		//} else {
		//	System.out.println("NOT FOUND!!!");
		//}
		//final SeContainer container = Weld.newInstance().initialize();
		//System.out.println("parsnip");
		//System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");

		//System.out.println("trace: " + BeanLogger.LOG.isTraceEnabled());
		//System.out.println("debug: " + BeanLogger.LOG.isDebugEnabled());
		//System.out.println("info: " + BeanLogger.LOG.isInfoEnabled());
		//LoggerFactory.getLogger("org.jboss.weld.logging").setLevel(Level.DEBUG);
		//Weld weld = Weld.class.newInstance();
		//final SeContainerInitializer initializer = SeContainerInitializer.newInstance();

		/*((Weld) initializer).containerId("hedgehog-main")
			.addServices(new GraalCDIProxyServices("hedgehog-main")
		);*/

		/*try ( SeContainer container = initializer.disableDiscovery()
			.addPackages(Main.class)
			.addBeanClasses(Application.class).initialize()) {
			container.select(Application.class).get().start();
			System.out.println(container.getBeanManager());
		}*/

		//Weld weld = Weld.class.newInstance();
		//SeContainerInitializer initializer = SeContainerInitializer.newInstance();
		//try (SeContainer container = initializer.disableDiscovery().addPackages(Main.class).initialize()) {
		//	container.select(Application.class);
		//}
	}

	/*public static void main(String[] args) {
		try {
			final SeContainer container = SeContainerInitializer.newInstance()
				.disableDiscovery()
				.addBeanClasses(classes().toArray(Class<?>[]::new))
				//.addProperty(DefiningClassService.class.getName(), ClassLoaderProxyService.Spy.class.getName()) // no unsafe usage
				//.addProperty("org.apache.webbeans.proxy.useStaticNames", "true") // a bit unsafe but otherwise no way to get pregenerated proxies
				.initialize();
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		Main main = new Main();
		main.run();
	}

	public void run() {
		//final Path out = Paths.get("C:\\Users\\Alex\\Desktop\\");
		//if (!Files.exists(out)) {
		//	throw new IllegalStateException("You should run compile before this task, missing: " + out);
		//}

		BeanManager beanManager = CDI.current().getBeanManager();

		beanManager.getBeans(Object.class).stream()
			.filter(b -> beanManager.isNormalScope(b.getScope()) && !isIgnoredBean(b)) // todo: do it also for interception
			.forEach(it -> {
				try { // triggers the proxy creation
					beanManager.getReference(it, Object.class, beanManager.createCreationalContext(null));
				} catch (final Exception ex) {
					ex.printStackTrace();
				}
			});

		final String config = ClassLoaderProxyService.Spy.class.cast(WebBeansContext.currentInstance().getService(DefiningClassService.class))
			.getProxies().entrySet().stream()
			.map(e
				-> {
				final Path target = out.resolve(e.getKey().replace('.', '/') + ".class");
				try {
					Files.createDirectories(target.getParent());
					Files.write(target, e.getValue());
				} catch (final IOException ex) {
					throw new IllegalStateException(ex);
				}
				System.out.println("Created proxy '{" + e.getKey() + "}'");
				return "<reflection>\n"
					+ "<name>" + e.getKey().replace("$$", "$$$$") + "</name>\n"
					+ "<allDeclaredConstructors>true</allDeclaredConstructors>\n"
					+ "<allDeclaredMethods>true</allDeclaredMethods>\n"
					+ "<allDeclaredFields>true</allDeclaredFields>\n"
					+ "</reflection>";
			})
			.collect(joining("\n"));
	}

	// CDI classes to register
	private static Stream<Class<?>> classes() {
		return Stream.of(Application.class);
	}

	private boolean isIgnoredBean(final Bean<?> b) { // we don't want a proxy for java.util.Set
		return (PassivationCapable.class.isInstance(b) && "apache.openwebbeans.OwbInternalConversationStorageBean".equals(PassivationCapable.class.cast(b).getId()))
			|| ExtensionBean.class.isInstance(b) //not needed;
	}*/
}
