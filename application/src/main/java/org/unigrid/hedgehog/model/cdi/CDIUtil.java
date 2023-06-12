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

package org.unigrid.hedgehog.model.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.jboss.weld.interceptor.util.proxy.TargetInstanceProxy;

@Slf4j
public class CDIUtil {
	public static <T> void instantiate(T proxy) {
		proxy.toString(); /* Will force CDI to instantiate this referenced insteance */
	}

	public static <T> T unproxy(T proxy) {
		return (T) ((TargetInstanceProxy) proxy).weld_getTargetInstance();
	}

	public static <T> void resolveAndRun(Class<T> clazz, Consumer<T> consumer) {
		final Instance<T> instance = CDI.current().select(clazz);

		if (instance.isResolvable()) {
			consumer.accept(instance.get());
		} else {
			log.atWarn().log("Unable to resolve instance {}", clazz);
		}
	}
}
