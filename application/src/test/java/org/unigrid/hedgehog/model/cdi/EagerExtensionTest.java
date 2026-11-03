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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Example;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.EagerTestClass;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.NonEagerTestClass;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.PreviewTestClass;

/* Run without --preview. Each test class has a JVM of its own, so the counts are those of this container alone. */
@WeldSetup(value = { EagerTestClass.class, NonEagerTestClass.class, PreviewTestClass.class },
	extensions = { EagerExtension.class }, scan = false)
public class EagerExtensionTest extends BaseMockedWeldTest {
	static final AtomicInteger EAGER_CREATED = new AtomicInteger();
	static final AtomicInteger NON_EAGER_CREATED = new AtomicInteger();
	static final AtomicInteger PREVIEW_CREATED = new AtomicInteger();

	@Inject private EagerTestClass eager;
	@Inject private NonEagerTestClass nonEager;
	@Inject private PreviewTestClass preview;

	@Eager @ApplicationScoped
	public static class EagerTestClass {
		@PostConstruct
		private void init() {
			EAGER_CREATED.incrementAndGet();
		}
	}

	@ApplicationScoped
	public static class NonEagerTestClass {
		@PostConstruct
		private void init() {
			NON_EAGER_CREATED.incrementAndGet();
		}
	}

	@Eager @Preview @ApplicationScoped
	public static class PreviewTestClass {
		@PostConstruct
		private void init() {
			PREVIEW_CREATED.incrementAndGet();
		}
	}

	@Example
	public void shouldInstantiateEagerly() {
		assertThat(EAGER_CREATED.get(), equalTo(1));
		assertThat(NON_EAGER_CREATED.get(), equalTo(0));
	}

	/* Injected and not vetoed, so that whatever uses a part of the preview still gets it */
	@Example
	public void shouldLeaveAPartOfThePreviewToItsFirstUse() {
		assertThat(PREVIEW_CREATED.get(), equalTo(0));

		CDIUtil.instantiate(preview);
		assertThat(PREVIEW_CREATED.get(), equalTo(1));
	}
}
