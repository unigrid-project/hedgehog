package org.unigrid.nativecdi;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

@ApplicationScoped
public class Application {
	private BeanManager beanManager;

	@Inject private ABean aBean;

	@PostConstruct
	private void init() {
		beanManager = CDI.current().getBeanManager();
		System.out.println("postconstruct");
		aBean.shit();
	}

	public void init(@Observes @Priority(Interceptor.Priority.APPLICATION - 100)
		@Initialized(ApplicationScoped.class) Object init) throws Exception {
		start();
	}

	public void start() throws Exception {
		System.out.println("Staring Application ...");

	}
}
