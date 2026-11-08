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

import jakarta.inject.Inject;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import org.unigrid.hedgehog.model.option.PreviewMockUp;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.EagerTestClass;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.NonEagerTestClass;
import org.unigrid.hedgehog.model.cdi.EagerExtensionTest.PreviewTestClass;

/* The beans of EagerExtensionTest, run with --preview. Each test class has a JVM of its own, so the counts are those of
   this container alone. */
@WeldSetup(value = { EagerTestClass.class, NonEagerTestClass.class, PreviewTestClass.class },
	extensions = { EagerExtension.class }, scan = false)
public class EagerExtensionPreviewTest extends BaseMockedWeldTest {
	/* The harness starts the container for the first bean a test asks for, which must not be one that it counts */
	@Inject private NonEagerTestClass nonEager;

	@BeforeContainer
	private static void turnThePreviewOn() {
		new PreviewMockUp();
	}

	@Example
	public void shouldInstantiateAPartOfThePreviewEagerly() {
		assertThat(EagerExtensionTest.EAGER_CREATED.get(), equalTo(1));
		assertThat(EagerExtensionTest.PREVIEW_CREATED.get(), equalTo(1));
		assertThat(EagerExtensionTest.NON_EAGER_CREATED.get(), equalTo(0));
	}
}
