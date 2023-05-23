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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.Channel;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Data
@Slf4j
@EqualsAndHashCode(callSuper = false)
public class PublishAndSaveSporkSchedule extends AbstractSchedule implements Schedulable {
	public PublishAndSaveSporkSchedule() {
		super(PublishSpork.DISTRIBUTION_FREQUENCY_MINUTES, TimeUnit.MINUTES, false);
	}

	private void save(SporkDatabase sporkDatabase) {
		final Instance<ApplicationDirectory> dir = CDI.current().select(ApplicationDirectory.class);

		if (dir.isResolvable()) {
			final Path path = Path.of(dir.get().getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);

			try {
				Files.createDirectories(dir.get().getUserDataDir());
				SporkDatabase.persist(path, sporkDatabase);

			} catch (Exception ex) {
				log.atWarn().log("Saving of spork database failed: {}", ex.getMessage());
				log.atTrace().log(() -> ex.toString());
			}
		}
	}

	@Override
	public Consumer<Channel> getConsumer() {
		return channel -> {
			final Instance<SporkDatabase> db = CDI.current().select(SporkDatabase.class);

			if (db.isResolvable()) {
				channel.writeAndFlush(PublishSpork.builder().gridSpork(db.get().getMintStorage()));
				channel.writeAndFlush(PublishSpork.builder().gridSpork(db.get().getMintSupply()));
				channel.writeAndFlush(PublishSpork.builder().gridSpork(db.get().getVestingStorage()));

				save(db.get());
			} else {
				log.atWarn().log("Unable to resolve SporkDatabase instance");
			}
		};
	}
}
