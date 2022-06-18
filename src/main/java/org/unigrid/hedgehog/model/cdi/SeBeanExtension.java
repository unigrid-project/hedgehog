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

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessInjectionTarget;
import jakarta.enterprise.inject.spi.WithAnnotations;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.inject.cdi.se.RequestScopeBean;
import org.glassfish.jersey.inject.cdi.se.bean.BeanHelper;
import org.glassfish.jersey.inject.cdi.se.injector.JerseyInjectionTarget;
import org.glassfish.jersey.internal.inject.Binding;
import org.glassfish.jersey.internal.inject.ClassBinding;
import org.glassfish.jersey.internal.inject.InjectionResolver;
import org.glassfish.jersey.internal.inject.InjectionResolverBinding;
import org.glassfish.jersey.internal.inject.InstanceBinding;
import org.glassfish.jersey.internal.inject.SupplierClassBinding;
import org.glassfish.jersey.internal.inject.SupplierInstanceBinding;
import org.jboss.weld.injection.producer.BasicInjectionTarget;

@Slf4j
public class SeBeanExtension implements Extension {
	private final List<JerseyInjectionTarget> targets = new ArrayList<>();

	public <T> void ignoreManuallyRegisteredComponents(
		@Observes @WithAnnotations({ Path.class, Provider.class }) ProcessAnnotatedType<T> pat) {
		/*log.atDebug().log("XXXXXX");
		System.out.println("ignoreManuallyRegisteredComponents XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		if (JerseyInjectionManager.getInjectables() == null) return;

		for (Binding binding : JerseyInjectionManager.getInjectables()) {
			if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
				final ClassBinding<?> classBinding = (ClassBinding<?>) binding;

				if (pat.getAnnotatedType().getJavaClass() == classBinding.getService()) {
					pat.veto();
					return;
				}
			} else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
				final InstanceBinding<?> instanceBinding = (InstanceBinding<?>) binding;

				if (pat.getAnnotatedType().getJavaClass() == instanceBinding.getService().getClass()) {
					pat.veto();
					return;
				}
			}
		}*/
	}

	public <T> void observeInjectionTarget(@Observes ProcessInjectionTarget<T> pit) {
		/*log.atDebug().log("XXXXXX");
		System.out.println("observeInjectionTarget XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		final BasicInjectionTarget<T> it = (BasicInjectionTarget<T>) pit.getInjectionTarget();

		final JerseyInjectionTarget<T> jerseyInjectionTarget = new JerseyInjectionTarget<>(it,
			pit.getAnnotatedType().getJavaClass()
		);

		targets.add(jerseyInjectionTarget);
		pit.setInjectionTarget(jerseyInjectionTarget);*/
	}

	public void registerBeans(@Observes AfterBeanDiscovery abd, BeanManager beanManager) {
		/*log.atDebug().log("XXXXXX");
		System.out.println("registerBeans XXXXXXXXXXXXXXXXXXXXXXXXXX ");
		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());
		//System.out.println(abd.);

		if (JerseyInjectionManager.getInjectables() == null) return;

		final List<InjectionResolver> injectionResolvers = JerseyInjectionManager.getInjectables().stream()
			.filter(binding -> InjectionResolverBinding.class.isAssignableFrom(binding.getClass()))
			.map(InjectionResolverBinding.class::cast)
			.map(InjectionResolverBinding::getResolver)
			.collect(Collectors.toList());

		targets.forEach(injectionTarget -> injectionTarget.setInjectionResolvers(injectionResolvers));

		System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + JerseyInjectionManager.getInjectables());

		for (Binding binding : JerseyInjectionManager.getInjectables()) {
			System.out.println("XXXXXXXXXXXXXXXXXXXXXXXXXX " + binding);
			if (ClassBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerBean((ClassBinding<?>) binding, abd, injectionResolvers, beanManager);

			} else if (InstanceBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerBean((InstanceBinding<?>) binding, abd, injectionResolvers);

			} else if (SupplierClassBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerSupplier((SupplierClassBinding<?>) binding, abd, injectionResolvers, beanManager);

			} else if (SupplierInstanceBinding.class.isAssignableFrom(binding.getClass())) {
				BeanHelper.registerSupplier((SupplierInstanceBinding<?>) binding, abd, beanManager);
			}
		}

		abd.addBean(new RequestScopeBean(beanManager));*/
	}
}
