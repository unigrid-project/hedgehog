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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class PublishAndSaveSporkSchedule extends AbstractSchedule {

    private static final Logger log = LoggerFactory.getLogger(PublishAndSaveSporkSchedule.class);
    private static final int INTERVAL_SECONDS = 15 * 60;

    @Override
    public int getPeriod() {
        return INTERVAL_SECONDS;
    }

    @Override
    public TimeUnit getTimeUnit() {
        return TimeUnit.SECONDS;
    }

    @Override
    public boolean isExecuteOnCreation() {
        return true;
    }

    private void saveDb(SporkDatabase db) {
        CDIUtil.resolveAndRun(ApplicationDirectory.class, dir -> {
            try {
                Path path = Path.of(dir.getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);
                Files.createDirectories(dir.getUserDataDir());
                SporkDatabase.persist(path, db);
            } catch (Exception ex) {
                log.warn("Saving of spork database failed", ex);
            }
        });
    }

    @Override
    public Consumer<Channel> getConsumer() {
        return channel -> CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
            if (channel != null) {
                channel.writeAndFlush(new PublishSpork(db.getMintStorage()));
                channel.writeAndFlush(new PublishSpork(db.getMintSupply()));
                channel.writeAndFlush(new PublishSpork(db.getVestingStorage()));
                channel.writeAndFlush(new PublishSpork(db.getStatisticsPubKey()));
            }
            saveDb(db);
        });
    }
}