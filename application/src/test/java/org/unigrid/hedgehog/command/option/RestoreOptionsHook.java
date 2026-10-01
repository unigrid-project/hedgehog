/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.option;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.lifecycle.AroundTryHook;
import net.jqwik.api.lifecycle.TryExecutionResult;
import net.jqwik.api.lifecycle.TryExecutor;
import net.jqwik.api.lifecycle.TryLifecycleContext;
import picocli.CommandLine.Option;

/*
   Picocli parses the options into static fields and takes whatever it finds there as their initial value, so an
   option one try sets would otherwise become the default of every try after it.
*/
public class RestoreOptionsHook implements AroundTryHook {
	private static final List<Field> OPTIONS = Stream.of(GridnodeOptions.class, NetOptions.class, RestOptions.class,
		SnapshotOptions.class).flatMap(type -> Arrays.stream(type.getDeclaredFields()))
		.filter(field -> field.isAnnotationPresent(Option.class)).peek(field -> field.setAccessible(true)).toList();

	@Override
	public TryExecutionResult aroundTry(TryLifecycleContext context, TryExecutor aTry, List<Object> parameters) {
		final Map<Field, Object> values = new HashMap<>();

		OPTIONS.forEach(field -> values.put(field, read(field)));

		try {
			return aTry.execute(parameters);
		} finally {
			values.forEach(RestoreOptionsHook::write);
		}
	}

	@SneakyThrows
	private static Object read(Field field) {
		return field.get(null);
	}

	@SneakyThrows
	private static void write(Field field, Object value) {
		field.set(null, value);
	}
}
