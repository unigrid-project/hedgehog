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

package org.unigrid.hedgehog.model.producer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.service.storage.InMemoryTransport;
import org.unigrid.hedgehog.service.storage.StorageDisabledException;

/* A database that a run with --preview filled holds a communication spork and a storage spork that the proxy and
   storage accept. A run without it acts on neither, so the proxy and storage stay off. */
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PreviewSporkProducerTest {
	private static final List<String> DAEMON = List.of("daemon");

	private static SporkDatabase databaseOfAPreview() {
		return SporkDatabase.builder().communicationSpork(new CommunicationSpork()).storageSpork(new StorageSpork())
			.build();
	}

	@SneakyThrows
	private static <T> T over(final T producer, final SporkDatabase database) {
		FieldUtils.writeField(producer, "sporkDatabase", database, true);
		return producer;
	}

	private static void run(final boolean preview) {
		if (preview) {
			OptionsCli.parse(DAEMON, "--preview");
		} else {
			OptionsCli.parse(DAEMON);
		}
	}

	@Property
	public void shouldTurnTheProxyOnOnlyWithThePreview(@ForAll boolean preview) {
		final ChainProducer producer = over(new ChainProducer(), databaseOfAPreview());

		run(preview);

		assertThat(producer.proxySettings(), equalTo(preview ? Optional.of(ProxySettings.DEFAULTS)
			: Optional.empty()));
		assertThat(producer.proxySettingsSource().current().isPresent(), is(preview));
	}

	@Property
	public void shouldTurnStorageOnOnlyWithThePreview(@ForAll boolean preview) {
		final StorageProducer producer = over(new StorageProducer(), databaseOfAPreview());

		run(preview);
		assertThat(producer.storageSpork().isPresent(), is(preview));
	}

	@Example
	public void shouldLeaveTheStorageServiceDisabledWithoutThePreview() {
		final StorageProducer producer = over(new StorageProducer(), databaseOfAPreview());

		run(false);

		assertThrows(StorageDisabledException.class, () -> producer.storageService(
			new TopologyGridnodeDirectory(List::of, () -> "self"), new InMemoryTransport())
			.store(new ByteArrayInputStream(new byte[1])));
	}
}
