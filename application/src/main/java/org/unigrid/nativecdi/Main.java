package org.unigrid.nativecdi;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import java.util.Iterator;
import java.util.ServiceLoader;
import org.jboss.weld.environment.se.Weld;
import org.unigrid.nativecdi.graal.GraalCDIProxyServices;

public class Main {
	public static void main(String[] args) throws Exception {
		ServiceLoader<Application> loader = ServiceLoader.load(Application.class);
		Iterator<Application> iterator = loader.iterator();
		if (iterator.hasNext()) {
			System.out.println("Service found");
		} else {
			System.out.println("NOT FOUND!!!");
		}
		//final SeContainer container = Weld.newInstance().initialize();
		//System.out.println("parsnip");
		//System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");

		//System.out.println("trace: " + BeanLogger.LOG.isTraceEnabled());
		//System.out.println("debug: " + BeanLogger.LOG.isDebugEnabled());
		//System.out.println("info: " + BeanLogger.LOG.isInfoEnabled());
		//LoggerFactory.getLogger("org.jboss.weld.logging").setLevel(Level.DEBUG);
		//Weld weld = Weld.class.newInstance();
		final SeContainerInitializer initializer = SeContainerInitializer.newInstance();

		((Weld) initializer).containerId("hedgehog-main")
			.addServices(new GraalCDIProxyServices("hedgehog-main")
		);

		try ( SeContainer container = initializer.disableDiscovery()
			.addPackages(Main.class)
			.addBeanClasses(Application.class).initialize()) {
			container.select(Application.class).get().start();
		}

		//Weld weld = Weld.class.newInstance();
		//SeContainerInitializer initializer = SeContainerInitializer.newInstance();
		//try (SeContainer container = initializer.disableDiscovery().addPackages(Main.class).initialize()) {
		//	container.select(Application.class);
		//}*/
	}
}
