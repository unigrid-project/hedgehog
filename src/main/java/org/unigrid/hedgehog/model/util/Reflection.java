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

package org.unigrid.hedgehog.model.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import lombok.extern.slf4j.Slf4j;

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
}
