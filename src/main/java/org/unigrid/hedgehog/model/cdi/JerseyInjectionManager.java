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

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.inject.spi.Unmanaged;
import jakarta.enterprise.inject.spi.Unmanaged.UnmanagedInstance;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ClassBinding;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.InjectionResolverBinding;
import org.glassfish.jersey.internal.inject.InstanceBinding;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.internal.inject.SupplierClassBinding;
import org.glassfish.jersey.internal.inject.SupplierInstanceBinding;

@Slf4j
public class JerseyInjectionManager implements InjectionManager {

	@Override
	public void completeRegistration() {
		/* Assume that the CDI context has been initialized previously and elsewhere */
	}

	@Override
	public void shutdown() {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public void register(Binding binding) {
		final BeanManager manager = CDI.current().getBeanManager();
		Set<Bean<?>> beans = manager.getBeans(binding.getImplementationType());

		if (binding instanceof ClassBinding) {
			log.atDebug().log("Class binding candidate for {} with scope {} and qualifiers {}",
				binding.getImplementationType(), binding.getScope(), binding.getQualifiers()
			);
		} else if (binding instanceof InjectionResolverBinding) {
			System.out.println(((InjectionResolverBinding) binding).getResolver().getAnnotation());
			System.out.println(((InjectionResolverBinding) binding).getImplementationType());
			return;
		} else if (binding instanceof InstanceBinding) {
			log.atDebug().log("Instance binding candidate for {} with scope {}",
				binding.getImplementationType(), binding.getScope()
			);

			JerseyExtension.getInstances().put(binding.getImplementationType(),
				((InstanceBinding) binding).getService()
			);
		} else if (binding instanceof SupplierClassBinding) {
			final Class<?> type = locateProducableType((SupplierClassBinding) binding);
			beans = manager.getBeans(type);

			log.atDebug().log("Candidate for supplier {} in scope {} for type {}",
				((SupplierClassBinding) binding).getSupplierClass(),
				((SupplierClassBinding) binding).getSupplierScope(), type
			);
		} else if (binding instanceof SupplierInstanceBinding) {
			final Class<?> type = locateProducableType((SupplierInstanceBinding) binding);
			beans = manager.getBeans(type);

			log.atDebug().log("Supplier instance binding candidate for {} with scope {}",
				((SupplierInstanceBinding) binding).getSupplier().get().getClass(), binding.getScope()
			);

			JerseyExtension.getSupplierInstances().put(binding.getImplementationType(),
				((SupplierInstanceBinding) binding).getSupplier()
			);
		} else {
			log.atError().log("Binding is of type {}", binding);
			throw new IllegalStateException("Binding type not supported by injection manager.");
		}

		if (beans.isEmpty()) {
			throw new IllegalStateException("No eligible candidate.");
		} else if (beans.size() > 1) {
			throw new IllegalStateException("Conflicting candidates.");
		}
	}

	@Override
	public void register(Iterable<Binding> itrbl) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@SneakyThrows
	private Class<?> locateProducableType(SupplierClassBinding binding) {
		return binding.getSupplierClass().getMethod("get").getReturnType();
	}

	@SneakyThrows
	private Class<?> locateProducableType(SupplierInstanceBinding binding) {
		return binding.getSupplier().get().getClass();
	}

	@Override
	public void register(Binder binder) {
		for (Binding b : binder.getBindings()) {
			register(b);
		}
	}

	@Override
	public void register(Object o) throws IllegalArgumentException {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public boolean isRegistrable(Class<?> type) {
		return false;
	}

	@Override
	public <T> T create(Class<T> type) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public <T> T createAndInitialize(Class<T> type) {
		final UnmanagedInstance<T> unmanaged = new Unmanaged<>(type).newInstance();
		return unmanaged.produce().postConstruct().get();
	}

	@Override
	public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> type, Annotation... antns) {
		return Collections.EMPTY_LIST;
		/* Already registered in the extension */
	}

	@Override
	public <T> T getInstance(Class<T> type, Annotation... antns) {
		final BeanManager manager = CDI.current().getBeanManager();
		final Set<Bean<?>> beans = manager.getBeans(type, antns);

		if (beans.isEmpty()) {
			return null;
		}

		Bean<?> bean = beans.iterator().next();
		final CreationalContext<?> ctx = manager.createCreationalContext(bean);
		return (T) manager.getReference(bean, type, ctx);
	}

	@Override
	public <T> T getInstance(Class<T> type, String string) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public <T> T getInstance(Class<T> type) {
		final BeanManager manager = CDI.current().getBeanManager();
		final Set<Bean<?>> beans = manager.getBeans(type);

		if (beans.isEmpty()) {
			return null;
		}

		Bean<?> bean = beans.iterator().next();
		final CreationalContext<?> ctx = manager.createCreationalContext(bean);
		return (T) manager.getReference(bean, type, ctx);
	}

	@Override
	public <T> T getInstance(Type type) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public Object getInstance(ForeignDescriptor fd) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public ForeignDescriptor createForeignDescriptor(Binding bndng) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public <T> List<T> getAllInstances(Type type) {
		/*final BeanManager manager = CDI.current().getBeanManager();
		final List<T> result = new ArrayList<>();

		for (Bean<?> bean : manager.getBeans(type)) {
			CreationalContext<?> ctx = manager.createCreationalContext(bean);
			Object reference = manager.getReference(bean, type, ctx);
			result.add((T) reference);
		}

		return result;*/
		return Collections.EMPTY_LIST;
		/* Already registered in the extension */
	}

	@Override
	public void inject(Object o) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public void inject(Object o, String string) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public void preDestroy(Object o) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

}
