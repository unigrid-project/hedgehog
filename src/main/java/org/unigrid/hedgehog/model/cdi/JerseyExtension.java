/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.cdi;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.WithAnnotations;
import jakarta.enterprise.inject.spi.configurator.BeanConfigurator;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.glassfish.jersey.server.AsyncContext;
import org.glassfish.jersey.server.ChunkedResponseWriter;
import org.glassfish.jersey.server.CloseableService;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.internal.JsonWithPaddingInterceptor;
import org.glassfish.jersey.server.internal.MappableExceptionWrapperInterceptor;
import org.glassfish.jersey.server.internal.monitoring.MonitoringContainerListener;
import org.glassfish.jersey.server.internal.process.RequestProcessingConfigurator;
import org.glassfish.jersey.server.internal.process.RequestProcessingContextReference;
import org.glassfish.jersey.server.internal.routing.UriRoutingContext;

@Slf4j
public class JerseyExtension implements Extension {
	public void registerScope(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
		//abd.addBean(new RequestScopeBean(beanManager));
	}

	public <T> void ignoreManuallyRegisteredComponents(@Observes @WithAnnotations({ Path.class, Provider.class })
		ProcessAnnotatedType<T> pat) {

		log.atDebug().log("XXX 0 {}", pat);
		/*System.out.println("ignoreManuallyRegisteredComponents XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		if (JerseyInjectionManager.getInjectables() == null) return;

		for (Binding binding : JerseyInjectionManager.getInjectables()) {
			if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
				final ClassBinding<?> classBinding = (ClassBinding<?>) binding;

				if (pat.getAnnotatedType().getJavaClass() == classBinding.getService()) {
					pat.veto();
					return;
				}
			} else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
				final InstanceBinding<?> instanceBinding = (InstanceBinding<?>) binding;

				if (pat.getAnnotatedType().getJavaClass() == instanceBinding.getService().getClass()) {
					pat.veto();
					return;
				}
			}
		}*/
	}

	public <T> void observeInjectionTarget(@Observes ProcessInjectionTarget<T> pit) {
		log.atDebug().log("XXX A1 {}", pit.getAnnotatedType());
		log.atDebug().log("XXX A2 {}", pit.getInjectionTarget());

		/*log.atDebug().log("XXXXXX");
		System.out.println("observeInjectionTarget XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		final BasicInjectionTarget<T> it = (BasicInjectionTarget<T>) pit.getInjectionTarget();

		final JerseyInjectionTarget<T> jerseyInjectionTarget = new JerseyInjectionTarget<>(it,
			pit.getAnnotatedType().getJavaClass()
		);

		targets.add(jerseyInjectionTarget);
		pit.setInjectionTarget(jerseyInjectionTarget);*/
	}

	private void registerSimple(AfterBeanDiscovery abd, Class<? extends Annotation> scope, Class<?>... singletons) {
		for (Class<?> s : singletons) {
			abd.addBean().types(s).scope(scope)
				.produceWith(o -> {
					try {
						return s.getDeclaredConstructor().newInstance();
					} catch(Exception ex) {
						/* Empty on purpose - we fall through and return an illegal state */
					}

					throw new IllegalStateException("Unable to register with CDI.");
				});
		}
	}

	private void registerRequestProcessingSupplier(AfterBeanDiscovery abd, Pair<Class<?>, String>... suppliers) {
		final String parent = "org.glassfish.jersey.server.internal.process.RequestProcessingConfigurator$";

		for (Pair <Class<?>, String> s : suppliers) {
			abd.addBean().types(s.getLeft()).produceWith(o -> {
				try {
					final RequestProcessingContextReference ref = CDI.current().select(
						RequestProcessingContextReference.class
					).get();

					final Class<?> supplierClass = Class.forName(parent + s.getRight());
					final Object instance = supplierClass.getDeclaredConstructor().newInstance(ref);
					final Method get = supplierClass.getMethod("get");

					return get.invoke(instance);

				} catch(ClassNotFoundException | IllegalAccessException | IllegalArgumentException
					| InstantiationException | InvocationTargetException | NoSuchMethodException ex) {

					throw new IllegalStateException("Failed to register supplier.");
				}
			});
		}
	}

	@SneakyThrows
	public void registerBeans(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
		registerSimple(abd, Singleton.class,
			ChunkedResponseWriter.class,
			JsonWithPaddingInterceptor.class,
			MappableExceptionWrapperInterceptor.class,
			MonitoringContainerListener.class
		);

		registerSimple(abd, RequestScoped.class,
			RequestProcessingContextReference.class,
			Class.forName("org.glassfish.jersey.server.internal.process.SecurityContextInjectee")
		);

		registerRequestProcessingSupplier(abd,
			Pair.of(AsyncContext.class, "AsyncContextFactory"),
			Pair.of(CloseableService.class, "CloseableServiceFactory"),
			Pair.of(ContainerRequest.class, "ContainerRequestFactory"),
			Pair.of(UriRoutingContext.class, "UriRoutingContextFactory")
		);
	}

	/*@SneakyThrows
	public void registerSuppliers(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
		final Class<?> supplier = Class.forName("org.glassfish.jersey.server.internal.process."
			+ "RequestProcessingConfigurator$ContainerRequestFactory"
		);

		final Object instance = supplier.getDeclaredConstructor().newInstance();
		final Method get = supplier.getMethod("get");

		abd.addBean().types(ContainerRequest.class).produceWith(o -> {
			try {
				return get.invoke(instance);
			} catch(IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
				throw new IllegalStateException("Method must be callable during supplier registration");
			}
		});

	}*/

		/*log.atDebug().log("XXXXXX");
		System.out.println("registerBeans XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());
		//System.out.println(abd.);

		if (JerseyInjectionManager.getInjectables() == null) return;

		final List<InjectionResolver> injectionResolvers = JerseyInjectionManager.getInjectables().stream()
			.filter(binding -> InjectionResolverBinding.class.isAssignableFrom(binding.getClass()))
			.map(InjectionResolverBinding.class::cast)
			.map(InjectionResolverBinding::getResolver)
			.collect(Collectors.toList());

		targets.forEach(injectionTarget -> injectionTarget.setInjectionResolvers(injectionResolvers));

		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		for (Binding binding : JerseyInjectionManager.getInjectables()) {
			System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + binding);
			if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerBean((ClassBinding<?>) binding, abd, injectionResolvers, beanManager);

			} else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerBean((InstanceBinding<?>) binding, abd, injectionResolvers);

			} else if (SupplierClassBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerSupplier((SupplierClassBinding<?>) binding, abd, injectionResolvers, beanManager);

			} else if (SupplierInstanceBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerSupplier((SupplierInstanceBinding<?>) binding, abd, beanManager);
			}
		}

		abd.addBean(new RequestScopeBean(beanManager));*/
}
