/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.MethodUtils;

@Slf4j
public class Reflection {
	public static void resetIllegalAccessLogger() throws IllegalAccessException,
		InvocationTargetException, NoSuchFieldException, NoSuchMethodException {

		Class<?> unsafeClass;
		Class<?> loggerClass;

		try {
			unsafeClass = Class.forName("sun.misc.Unsafe");
			loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger");

		} catch (ClassNotFoundException ex) {
			log.warn("Unable to choke IllegalAccessLoger", ex);
			return; /* Bail out, as it means we are on a Java release where we need to ignore this */
		}

		final Field field = unsafeClass.getDeclaredField("theUnsafe");

		field.setAccessible(true);
		Object unsafe = field.get(null);

		final Field loggerField = loggerClass.getDeclaredField("logger");
		final Method staticFieldOffset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field.class);
		final Method putObjectVolatile = unsafeClass.getDeclaredMethod("putObjectVolatile",
			Object.class, long.class, Object.class
		);

		final long offset = (long) staticFieldOffset.invoke(unsafe, loggerField);
		putObjectVolatile.invoke(unsafe, loggerClass, offset, null);
	}

	public static <T> Set<Field> getDeclaredFieldsWithParents(Class<T> clazz) {
		final Set<Field> fields = new HashSet<>();

		/* Class.getFields() only fetches public fields in the class hierarchy. On the other hand,
		Class.getDeclaredFields() fetches all fields regardless of accessor. So, in order to get all fields in the
		class hierarchy (not just the public ones), we have to loop through the hierachy and use
		Class.getDeclaredFields(). */

		fields.addAll(Arrays.asList(clazz.getDeclaredFields()));

		if (Objects.nonNull(clazz.getSuperclass())) {
			fields.addAll(getDeclaredFieldsWithParents(clazz.getSuperclass()));
		}

		return fields;
	}

	public static Constructor<?> getConstructor(String name, Class<?>... classes)
		throws ClassNotFoundException, NoSuchMethodException {

		final Class<?> clazz = Class.forName(name);
		final Constructor<?> constructor = clazz.getDeclaredConstructor(classes);

		constructor.setAccessible(true);
		return constructor;
	}

	public static <T, I> T invoke(I instance, String name, Object... arguments)
		throws ClassNotFoundException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {

		return (T) MethodUtils.invokeMethod(instance, true, name, arguments);
	}
}
