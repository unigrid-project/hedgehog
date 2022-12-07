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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import net.jqwik.api.domains.Domain;
import net.jqwik.api.domains.DomainContext;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.unigrid.hedgehog.model.ApplicationDirectoryMockUp;

@Domain(DomainContext.Global.class)
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = WeldHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BaseMockedWeldTest {
	@BeforeContainer
	private static void beforeContainer() {
		new ApplicationDirectoryMockUp();
	}
}
