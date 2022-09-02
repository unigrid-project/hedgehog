/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.jqwik;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.lifecycle.AroundContainerHook;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtension;

public class WeldHook implements AroundContainerHook, AroundPropertyHook {
	private List<Class<?>> findWeldClasses(Object instance, Class<?> clazz) {
		final List<Class<?>> beans = new ArrayList<>();

		for (Method m : clazz.getDeclaredMethods()) {
			if (m.isAnnotationPresent(WeldSetup.class)) {
				m.setAccessible(true);

				try {
					beans.addAll((List<Class<?>>) m.invoke(instance));
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException ex) {
					ex.printStackTrace(); /* Shouldn't happen */
				}
			}
		}

		return beans;
	}

	private Type[] getGenericTypes(Field f) {
		return ((ParameterizedType) f.getGenericType()).getActualTypeArguments();
	}

	@Override
	public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
		for (Field f : context.testInstance().getClass().getDeclaredFields()) {
			if (f.isAnnotationPresent(Inject.class)) {
				f.setAccessible(true);

				if (Instance.class.equals(f.getType())) {
					for (Type t : getGenericTypes(f)) {
						final BeanManager bm = CDI.current().getBeanManager();

						try {
							final Class<?> clazz = Class.forName(t.getTypeName());
							final Instance<?> instance = bm.createInstance().select(clazz);

							f.set(context.testInstance(), instance);
						} catch (ClassNotFoundException | IllegalAccessException
							| IllegalArgumentException ex) {
							ex.printStackTrace();
						}

						break; /* Only care about the first generic type */
					}
				} else {
					CDI.current().select(f.getType()).forEach(v -> {
						try {
							f.set(context.testInstance(), v);
						} catch (IllegalAccessException | IllegalArgumentException ex) {
							ex.printStackTrace();
						}
					});
				}
			}
		}

		return property.execute();
	}

	@Override
	public void beforeContainer(ContainerLifecycleContext context) {
		context.optionalContainerClass().ifPresent(c -> {
			final Object instance = context.newInstance(c);
			final List<Class<?>> beans = findWeldClasses(instance, c);

			final Weld weld = WeldInitiator.createWeld().addExtension(new EagerExtension())
				.beanClasses(beans.toArray(new Class<?>[0]));

			weld.initialize();
		});
	}
}
