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
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.jqwik.api.lifecycle.AroundContainerHook;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
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

	private WeldContainer createWeld(String name, PropertyLifecycleContext context) {
		final List<Class<?>> weldClasses = findWeldClasses(context.testInstance(), context.containerClass());

		return new Weld(name).addExtension(new EagerExtension())
			.disableDiscovery().beanClasses(weldClasses.toArray(new Class<?>[0]))
			.initialize();
	}

	private Type getGenericType(Field f) {
		return ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
	}

	private boolean getRealClass(Class<?> clazz) throws ClassNotFoundException {
		
		/*if (Instance.class.equals(field.getType())) {
			System.out.println("mmmm pickle pickle");
		} else {
			System.out.println("NO PICKLES!!! " + field.getType());
		}*/

		System.out.println(clazz);

		return true;
	}

	private Stream<?> forEachListEntry(Field field, PropertyLifecycleContext context, Consumer<Class<?>> consumer) {
		try {
			/* Only inject on the first run or if empty - otherwise we assume everything is already injected */
			if (List.class.equals(field.getType()) && field.isAnnotationPresent(Inject.class)
				&& Objects.isNull(field.get(context.testInstance()))) {

				if (!field.isAnnotationPresent(Instances.class)) {
					throw new IllegalStateException("Lists require an instances annotation.");
				}

				final List entries = (List) field.get(context.testInstance());
				final List<Class<?>> weldClasses = findWeldClasses(context.testInstance(),
					context.containerClass()
				);

				field.set(context.testInstance(), new ArrayList());

				for (int i = 0; i < field.getAnnotation(Instances.class).value(); i++) {
					final String testClassName = context.containerClass().getSimpleName() + i;
					NamedCDIProvider.getNameReference().set(testClassName);

					//field
					//getGenericType(field).
					//getRealClass(clazz);

					//final WeldContainer container = createWeld(testClassName, context);
					//System.out.println(clazz.get);
					consumer.accept(this.getClass());
				}
			}
		} catch(ClassCastException | IllegalAccessException ex) {
			ex.printStackTrace();
		}

		return Stream.empty();
	}

	private Stream<Field> forEachInjectionPoint(PropertyLifecycleContext context, Consumer<Field> plainConsumer) {
		final List<Field> lists = new ArrayList<>();

		for (Field f : context.testInstance().getClass().getDeclaredFields()) {
			if (f.isAnnotationPresent(Inject.class)) {
				f.setAccessible(true);

				if (List.class.equals(f.getType())) {
					lists.add(f);
				} else {
					plainConsumer.accept(f);
				}
			}
		}

		return lists.stream();
	}
		

	@Override
	public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
		final String className = context.containerClass().getSimpleName();

		forEachInjectionPoint(context, f -> {
			;
		});
				

				/*try {
					injectList(f, context);
				} catch (ClassNotFoundException | IllegalAccessException ex) {
					ex.printStackTrace();
				}*/

				/*if (Instance.class.equals(f.getType())) {
					final BeanManager bm = CDI.current().getBeanManager();

					try {
						final Class<?> clazz = Class.forName(type.getTypeName());
						final Instance<?> instance = bm.createInstance().select(clazz);

						f.set(context.testInstance(), instance);
					} catch (ClassNotFoundException | IllegalAccessException
						| IllegalArgumentException ex) {
						ex.printStackTrace();
					}

					break; *//* Only care about the first generic type */
 /*} else {
					CDI.current().select(f.getType()).forEach(v -> {
						try {
							f.set(context.testInstance(), v);
						} catch (IllegalAccessException | IllegalArgumentException ex) {
							ex.printStackTrace();
						}
					});
				}*/
		//}

		return property.execute();
	}

	@Override
	public void beforeContainer(ContainerLifecycleContext context) {
		CDI.setCDIProvider(new NamedCDIProvider());
	}
}
