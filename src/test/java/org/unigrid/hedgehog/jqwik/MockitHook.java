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

import java.lang.reflect.Method;
import java.util.Optional;
import mockit.integration.TestRunnerDecorator;
import net.jqwik.api.lifecycle.AroundPropertyHook;
import net.jqwik.api.lifecycle.AroundContainerHook;
import net.jqwik.api.lifecycle.ResolveParameterHook;
import net.jqwik.api.lifecycle.ContainerLifecycleContext;
import net.jqwik.api.lifecycle.LifecycleContext;
import net.jqwik.api.lifecycle.Lifespan;
import net.jqwik.api.lifecycle.ParameterResolutionContext;
import net.jqwik.api.lifecycle.PropertyExecutionResult;
import net.jqwik.api.lifecycle.PropertyExecutor;
import net.jqwik.api.lifecycle.PropertyLifecycleContext;
import net.jqwik.api.lifecycle.Store;

public class MockitHook extends TestRunnerDecorator
	implements AroundPropertyHook, AroundContainerHook, ResolveParameterHook {

	private static final String STORE_NAME = MockitHook.class.getSimpleName();

	@Override
	public PropertyExecutionResult aroundProperty(PropertyLifecycleContext context, PropertyExecutor property) {
		Store.create(STORE_NAME, Lifespan.PROPERTY, () -> {
			return context.testInstance();
		});

		handleMockFieldsForWholeTestClass(context.testInstance());

		final PropertyExecutionResult result = property.execute();
		prepareForNextTest();
		return result;
	}

	@Override
	public void afterContainer(ContainerLifecycleContext context) {
		cleanUpAllMocks();
	}

	@Override
	public Optional<ParameterSupplier> resolve(ParameterResolutionContext prc, LifecycleContext lc) {
		//Store.get(STORE_NAME + prc.optionalMethod().get().getName()).get();

		if (prc.optionalMethod().isPresent()) {
			final Method method = prc.optionalMethod().get();

			final Object[] instances = Store.getOrCreate(STORE_NAME + method.getName(),
				Lifespan.PROPERTY, () -> {

				return createInstancesForAnnotatedParameters(
					Store.get(STORE_NAME).get(), method, null
				);
			}).get();

			return Optional.of(o -> instances[prc.index()]);
		}

		return Optional.empty();
	}
}
