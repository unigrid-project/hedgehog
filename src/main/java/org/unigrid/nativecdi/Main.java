package org.unigrid.nativecdi;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import org.jboss.weld.logging.BeanLogger;

public class Main {
    public static void main(String[] args) throws Exception {
	System.out.println("parsnip");
	System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace");

	System.out.println("trace: " + BeanLogger.LOG.isTraceEnabled());
	System.out.println("debug: " + BeanLogger.LOG.isDebugEnabled());
	System.out.println("info: " + BeanLogger.LOG.isInfoEnabled());

	//LoggerFactory.getLogger("org.jboss.weld.logging").setLevel(Level.DEBUG);
        //Weld weld = Weld.class.newInstance();
	SeContainerInitializer initializer = SeContainerInitializer.newInstance();

        try (SeContainer container = initializer.disableDiscovery().addPackages(Main.class).initialize()) {
            container.select(Application.class).get().start();
        }

	System.out.println(CDI.current().getBeanManager());
    }
}
