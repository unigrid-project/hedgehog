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

package org.unigrid.hedgehog.model.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.AfterDeploymentValidation;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.PreviewOptions;

/* A bean of the preview is not vetoed, as others inject it: without --preview it is only not started, and so is
   created by its first use, if any */
@Slf4j
public class EagerExtension implements Extension {
	private final List<Bean<?>> eagerBeansList = new ArrayList<>();
	private final Set<Bean<?>> previewBeans = new HashSet<>();

	public <T> void collect(@Observes ProcessBean<T> event) {
		final Annotated annotated = event.getAnnotated();

		if (annotated.isAnnotationPresent(Eager.class) && annotated.isAnnotationPresent(ApplicationScoped.class)) {
			eagerBeansList.add(event.getBean());

			if (annotated.isAnnotationPresent(Preview.class)) {
				previewBeans.add(event.getBean());
			}
		}
	}

	public void load(@Observes AfterDeploymentValidation event, BeanManager beanManager) {
		final boolean preview = PreviewOptions.isPreview();

		eagerBeansList.stream().filter(bean -> preview || !previewBeans.contains(bean)).forEach((bean) -> {
			log.atDebug().log("@Eager instantiation detected on {}", bean);

			beanManager.getReference(bean, bean.getBeanClass(),
				beanManager.createCreationalContext(bean)
			).toString();
		});
	}
}
