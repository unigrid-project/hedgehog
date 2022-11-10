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

import com.evolvedbinary.j8fu.function.TriConsumer;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import net.jqwik.api.lifecycle.AroundContainerHook;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import org.apache.commons.lang3.StringUtils;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.util.Reflection;

public class WeldHook implements AroundContainerHook, AroundPropertyHook {
	private List<Class<?>> findWeldClasses(Object instance, Class<?> clazz) {
		final Set<Class<?>> beans = new HashSet<>();
		final WeldSetup weldSetup = clazz.getAnnotation(WeldSetup.class);

		if (Objects.nonNull(weldSetup)) {
			beans.addAll(List.of(weldSetup.value()));
		}

		return new ArrayList<>(beans);
	}

	private WeldContainer createOrGetWeld(String name, PropertyLifecycleContext context) {
		WeldContainer instance = WeldContainer.instance(name);

		if (Objects.isNull(instance)) {
			final List<Class<?>> weldClasses = findWeldClasses(context.testInstance(), context.containerClass());

			instance = new Weld(name)
				.enableDiscovery()
				.beanClasses(weldClasses.toArray(new Class<?>[0]))
				.interceptors(ProtectedInterceptor.class)
				.initialize();
		}

		return instance;
	}

	private Type getGenericType(Field f) {
		return ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
	}

	private List<Field> forEachInjectionPoint(Type type, Field field, PropertyLifecycleContext context,
		BiConsumer<Type, Field> consumer) {

		final List<Field> lists = new ArrayList<>();

		if (field.isAnnotationPresent(Inject.class)) {
			field.setAccessible(true);

			if (List.class.equals(type)) {
				lists.add(field);
			} else if (Instance.class.equals(type)) {
				consumer.accept(getGenericType(field), field);
			} else {
				consumer.accept(type, field);
			}
		}

		return lists;
	}

	private void forEachListInjectionPoint(List<Field> fields, PropertyLifecycleContext context,
		TriConsumer<List<Object>, Type, Field> consumer) {

		try {
			for (Field f : fields) {
				/* Only inject on the first run or if empty - otherwise we assume everything is injected */
				if (List.class.equals(f.getType()) && f.isAnnotationPresent(Inject.class)
					&& Objects.isNull(f.get(context.testInstance()))) {

					if (!f.isAnnotationPresent(Instances.class)) {
						throw new IllegalStateException("Lists require an instances annotation.");
					}

					final List entries = (List) f.get(context.testInstance());
					final List<Class<?>> weldClasses = findWeldClasses(context.testInstance(),
						context.containerClass()
					);

					final List<Object> instances = new ArrayList();
					f.set(context.testInstance(), instances);

					for (int i = 0; i < f.getAnnotation(Instances.class).value(); i++) {
						consumer.accept(instances, getGenericType(f), f);
					}
				}
			}
		} catch (ClassCastException | IllegalAccessException ex) {
			ex.printStackTrace();
		}
	}

	private Object inject(PropertyLifecycleContext context, Type type, Field field, String containerSuffix) {
		final String name = context.containerClass().getSimpleName() + containerSuffix;
		final WeldContainer instance = createOrGetWeld(name, context);
		NamedCDIProvider.NAME_REFERENCE.set(name);

		try {
			final Object obj = instance.select(type).get();

			if (Objects.nonNull(field)) {
				field.set(context.testInstance(), obj);
			}

			return obj;

		} catch (IllegalAccessException ex) {
			ex.printStackTrace();
			throw new IllegalStateException("Injection failed.");
		}
	}

	@Override
	public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
		final Set<Field> fields = Reflection.getDeclaredFieldsWithParents(context.testInstance().getClass());

		fields.stream().forEach(f -> {
			final List<Field> listFields = forEachInjectionPoint(f.getType(), f, context, (type, field) -> {
				inject(context, type, field, StringUtils.EMPTY);
			});

			forEachListInjectionPoint(listFields, context, (instances, type, field) -> {
				forEachInjectionPoint(type, field, context, (secondaryType, secondaryField) -> {
					instances.add(inject(context, type, null, Integer.toString(instances.size() + 1)));
				});
			});
		});

		return property.execute();
	}

	@Override
	public void beforeContainer(ContainerLifecycleContext context) {
		CDI.setCDIProvider(new NamedCDIProvider());
	}
}
