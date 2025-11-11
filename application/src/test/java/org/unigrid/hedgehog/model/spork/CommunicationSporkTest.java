/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.spork;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class CommunicationSporkTest {
	@Example
	public void hasTheProxyDefaults() {
		final SporkData data = new CommunicationSpork().getData();

		assertThat(data.getGroupSize(), equalTo(8));
		assertThat(data.getReorganizePeriodSeconds(), equalTo(3600L));
		assertThat(data.getChainsPerGroup(), equalTo(4));
		assertThat(data.getClientsPerChain(), equalTo(16));
		assertThat(data.getChainCapacityBytes(), equalTo(33_554_432L));
		assertThat(data.getRetentionSeconds(), equalTo(30));
		assertThat(data.getMaxEntryBytes(), equalTo(16_384L));
		assertThat(data.getSessionIdleSeconds(), equalTo(300));
	}

	@Example
	public void isRegisteredAsASporkType() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final GridSpork spork = GridSpork.create(GridSpork.Type.COMMUNICATION);

		assertThat(GridSpork.Type.get((short) 1040), equalTo(GridSpork.Type.COMMUNICATION));
		assertThat(spork.getType(), equalTo(GridSpork.Type.COMMUNICATION));

		sporkDatabase.set(spork);
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(spork));
	}

	@Example
	public void emptyIsAFreshInstanceOfTheDefaults() {
		final SporkData data = new CommunicationSpork().getData();
		data.setGroupSize(99);
		data.setSessionIdleSeconds(1);

		final SporkData empty = data.empty();

		assertThat(empty, equalTo(new SporkData()));
		assertThat(empty, not(sameInstance(data)));
		assertThat(empty, not(sameInstance(data.empty())));
	}

	@Example
	public void pinsTheSerialVersionOfItsSignedData() throws ReflectiveOperationException {
		final Field version = SporkData.class.getDeclaredField("serialVersionUID");
		version.setAccessible(true);

		assertThat(Modifier.isStatic(version.getModifiers()), is(true));
		assertThat(Modifier.isFinal(version.getModifiers()), is(true));
		assertThat(version.getType(), equalTo(long.class));
		assertThat(version.getLong(null), equalTo(1L));
	}
}
