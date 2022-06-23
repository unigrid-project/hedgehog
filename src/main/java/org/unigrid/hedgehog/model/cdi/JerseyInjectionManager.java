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

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.internal.inject.Binder;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ClassBinding;
import org.glassfish.jersey.internal.inject.ForeignDescriptor;
import org.glassfish.jersey.internal.inject.InjectionManager;
import org.glassfish.jersey.internal.inject.InstanceBinding;
import org.glassfish.jersey.internal.inject.ServiceHolder;
import org.glassfish.jersey.internal.inject.SupplierClassBinding;

@Slf4j
public class JerseyInjectionManager implements InjectionManager {

	@Override
	public void completeRegistration() {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public void shutdown() {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public void register(Binding binding) {
		final BeanManager manager = CDI.current().getBeanManager();
		final Set<Bean<?>> beans = manager.getBeans(binding.getImplementationType());;

		if (binding instanceof ClassBinding) {
			log.atDebug().log("Candidate for {} with scope {} and qualifiers {}",
				binding.getImplementationType(), binding.getScope(), binding.getQualifiers()
			);

			/*} else if (b instanceof SupplierClassBinding) {
			final Class<?> type = locateProducableType((SupplierClassBinding) b);
			beans = manager.getBeans(type);

			log.atDebug().log("Candidate for supplier {} in scope {} for type {}",
				((SupplierClassBinding) b).getSupplierClass(),
				((SupplierClassBinding) b).getSupplierScope(), type
			);*/
		} else if (binding instanceof InstanceBinding) {
			System.out.println(((InstanceBinding) binding).getService());
			System.out.println(((InstanceBinding) binding).getImplementationType());
			System.out.println(((InstanceBinding) binding).getScope());
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
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@SneakyThrows
	private Class<?> locateProducableType(SupplierClassBinding binding) {
		return binding.getSupplierClass().getMethod("get").getReturnType();
	}

	@Override
	public void register(Binder binder) {
		final BeanManager manager = CDI.current().getBeanManager();
		Set<Bean<?>> beans;

		for (Binding b : binder.getBindings()) {
			if (b instanceof ClassBinding) {
				beans = manager.getBeans(b.getImplementationType());

				log.atDebug().log("Candidate for {} with scope {} and qualifiers {}",
					b.getImplementationType(), b.getScope(), b.getQualifiers()
				);
			} else if (b instanceof SupplierClassBinding) {
				final Class<?> type = locateProducableType((SupplierClassBinding) b);
				beans = manager.getBeans(type);

				log.atDebug().log("Candidate for supplier {} in scope {} for type {}",
					((SupplierClassBinding) b).getSupplierClass(),
					((SupplierClassBinding) b).getSupplierScope(), type
				);
			} else {
				log.atError().log("Binding is of type {}", b);
				throw new IllegalStateException("Binding type not supported by injection manager.");
			}

			if (beans.isEmpty()) {
				throw new IllegalStateException("No eligible candidate.");
			} else if (beans.size() > 1) {
				throw new IllegalStateException("Conflicting candidates.");
			}
		}
	}

	@Override
	public void register(Object o) throws IllegalArgumentException {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public boolean isRegistrable(Class<?> type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T create(Class<T> type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T createAndInitialize(Class<T> type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> List<ServiceHolder<T>> getAllServiceHolders(Class<T> type, Annotation... antns) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T getInstance(Class<T> type, Annotation... antns) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T getInstance(Class<T> type, String string) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T getInstance(Class<T> type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> T getInstance(Type type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public Object getInstance(ForeignDescriptor fd) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public ForeignDescriptor createForeignDescriptor(Binding bndng) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public <T> List<T> getAllInstances(Type type) {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
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
