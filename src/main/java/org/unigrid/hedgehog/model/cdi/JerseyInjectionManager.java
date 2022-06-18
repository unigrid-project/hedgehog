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

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.glassfish.jersey.inject.cdi.se.CdiSeInjectionManager;
import org.glassfish.jersey.inject.cdi.se.injector.ContextInjectionResolverImpl;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.Bindings;
import org.glassfish.jersey.internal.inject.ClassBinding;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.InjectionResolver;

@Slf4j
public class JerseyInjectionManager extends CdiSeInjectionManager {
	//boolean externalContainer = true;
	//final Map<Class<?>, Binding> externalBindings = new HashMap<>();

	public JerseyInjectionManager() {
		super();

		/*try {
			super.setBeanManager(CDI.current().getBeanManager());
			super.setContainer((SeContainer) CDI.current());
		} catch (IllegalStateException ex) {
			log.atDebug().log("Using built-in Jersey container for CDI.");
			externalContainer = false;
		}*/
	}

	@Override
	public void completeRegistration() throws IllegalStateException {
		/*if (externalContainer) {
			getBindings().bind(Bindings.service(this).to(InjectionManager.class));
			getBindings().install(new ContextInjectionResolverImpl.Binder(this::getBeanManager));
		} else {
			super.completeRegistration();
			// No running CDI instance? Then we use Jerseys own container instead
		}*/
		super.completeRegistration();
		
	}

	@Override
	public void register(Binding binding) {
		//CDI.current().getBeanManager().
		/*final List<InjectionResolver> injectionResolvers = JerseyInjectionManager.getInjectables().stream()
			.filter(binding -> InjectionResolverBinding.class.isAssignableFrom(binding.getClass()))
			.map(InjectionResolverBinding.class::cast)
			.map(InjectionResolverBinding::getResolver)
			.collect(Collectors.toList());
		
		if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
			BeanHelper.registerBean((ClassBinding<?>) binding, abd, injectionResolvers, beanManager);

		} else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
			CDI.current().getBeanManager().

		} else if (SupplierClassBinding.class.isAssignableFrom(binding.getClass())) {
			BeanHelper.registerSupplier((SupplierClassBinding<?>) binding, abd, injectionResolvers, beanManager);

		} else if (SupplierInstanceBinding.class.isAssignableFrom(binding.getClass())) {
			BeanHelper.registerSupplier((SupplierInstanceBinding<?>) binding, abd, beanManager);
		}*/

		/*if (externalContainer) {
			log.atDebug().log("Registering binding {} of type {}", binding, binding.getImplementationType());
			//externalBindings.put(binding.getImplementationType(), binding);
		} else {
			super.register(binding);
		}*/

		super.register(binding);
	}

	@Override
	public void inject(Object instance) {
		System.out.println("UUUUUUU : " + instance);
		super.inject(instance);
	}

	@Override
	public <T> T getInstance(Type contractOrImpl) {
		//if (externalContainer) {
			final Set<Bean<?>> beans = getBeanManager().getBeans(contractOrImpl);

			if (CollectionUtils.isEmpty(beans)) {
				log.atDebug().log("Shit {}", contractOrImpl);
				//return null;
				// Seems to be what Jersey wants
			}

			final Bean<T> bean = (Bean<T>) getBeanManager().resolve(beans);
			log.atDebug().log("Getting instance {} from container", bean.getBeanClass());

		/*	return (T) getBeanManager().getReference(bean, contractOrImpl,
				getBeanManager().createCreationalContext(bean)
			);

			//final Instance<T> instance = CDI.current().select(contractOrImpl, qualifiers);
			//log.atDebug().log("Getting instance {} from container", instance.toString());
			//return (T) instance;
		}

		return super.getInstance(contractOrImpl);*/
		return super.getInstance(contractOrImpl);
	}

	@Override
	public <T> T getInstance(Class<T> contractOrImpl) {
		//System.out.println("AAAA");
		return getInstance(contractOrImpl, new Annotation[]{ /* Empty on purpose */});
	}

	@Override
	public <T> T getInstance(Class<T> contractOrImpl, Annotation... qualifiers) {
		//if (externalContainer) {
			final Set<Bean<?>> beans = getContainer() != null && getBeanManager() != null ? getBeanManager().getBeans(contractOrImpl, qualifiers) : Collections.emptySet();

			if (CollectionUtils.isEmpty(beans)) {
				log.atDebug().log("Shit {}", contractOrImpl);
				//return null;
				// Seems to be what Jersey wants
			} else {

			final Bean<T> bean = (Bean<T>) getBeanManager().resolve(beans);
			log.atDebug().log("Getting instance {} from container", bean.getBeanClass());
			}

		/*	return (T) getBeanManager().getReference(bean, contractOrImpl,
				getBeanManager().createCreationalContext(bean)
			);

			//final Instance<T> instance = CDI.current().select(contractOrImpl, qualifiers);
			//log.atDebug().log("Getting instance {} from container", instance.toString());
			//return (T) instance;
		}

		return super.getInstance(contractOrImpl);*/
		return super.getInstance(contractOrImpl, qualifiers);
	}

	@Override
	public void setBeanManager(BeanManager beanManager) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setContainer(SeContainer container) {
		throw new UnsupportedOperationException();
	}
}
