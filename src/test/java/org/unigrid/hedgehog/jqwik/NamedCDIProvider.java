package org.unigrid.hedgehog.jqwik;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.CDIProvider;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.junit5.WeldInitiator;
import org.unigrid.hedgehog.model.cdi.EagerExtension;
import org.unigrid.hedgehog.model.producer.RandomUUIDProducer;

public class NamedCDIProvider implements CDIProvider {
	@Getter private static final AtomicReference<String> nameReference = new AtomicReference<>();
	//private final ConcurrentMap<String, WeldContainer> containers = new ConcurrentHashMap<>();

	@Override
	public CDI<Object> getCDI() {
		System.out.println("SHITPICKLE! " + nameReference.get());

		if (Objects.isNull(nameReference.get())) {
			System.out.println("No namespace set for requested CDI instance.");
			throw new IllegalStateException("No namespace set for requested CDI instance.");
		}

		//Weld container = WeldContainer.instance(nameReference.get()).
		//System.out.println(container);

		//if (Objects.isNull(container)) {
			System.out.println("shit1");
			System.out.println(new Weld("shit"));
			System.out.println(new Weld(nameReference.get()).disableDiscovery());
			//new Weld(nameReference.get() + "XX").disableDiscovery().initialize();
			System.out.println("shit2");
		//}

		System.out.println("shit");
		return new Weld(nameReference.get()).disableDiscovery().beanClasses(RandomUUIDProducer.class).initialize();
		//new Weld(nameReference.get() + "AA").disableDiscovery().initialize();

		//final Weld weld = WeldInitiator.createWeld().addExtension(new EagerExtension())
		//		.beanClasses(beans.toArray(new Class<?>[0]));

	}

	@Override
	public int getPriority() {
		return DEFAULT_CDI_PROVIDER_PRIORITY + 10; /* Bump ourselves up so we get precedence. */
	}
}
