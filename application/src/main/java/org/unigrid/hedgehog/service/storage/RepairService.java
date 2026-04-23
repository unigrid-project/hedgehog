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

package org.unigrid.hedgehog.service.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.cdi.Eager;

@Slf4j
@Eager
@ApplicationScoped
public class RepairService {
	private static final int CHECK_MINUTES = 1;
	private static final String THREAD_NAME = "storage-repair";

	@Inject
	private GroupRepairer repairer;

	private ScheduledExecutorService executor;
	private OptionalLong lastEpoch = OptionalLong.empty();

	@PostConstruct
	private void start() {
		executor = Executors.newSingleThreadScheduledExecutor(RepairService::daemon);
		executor.scheduleWithFixedDelay(this::tick, CHECK_MINUTES, CHECK_MINUTES, TimeUnit.MINUTES);
	}

	private static Thread daemon(final Runnable task) {
		final Thread thread = new Thread(task, THREAD_NAME);

		thread.setDaemon(true);
		return thread;
	}

	/* A scheduled task that throws is never run again, so nothing may escape a round */
	void tick() {
		try {
			runDueRound();
		} catch (Throwable failure) {
			report(failure);
		}
	}

	/* The first epoch seen only marks the start, so a restart waits a full interval for the topology to settle
	   before it touches anything */
	private void runDueRound() {
		final OptionalLong epoch = repairer.currentEpoch();

		if (epoch.isEmpty() || epoch.equals(lastEpoch)) {
			return;
		}

		final boolean started = lastEpoch.isPresent();

		lastEpoch = epoch;

		if (started) {
			repairer.runEpoch();
		}
	}

	private static void report(final Throwable failure) {
		if (failure instanceof Error) {
			log.atError().log("A storage repair round failed: {}", failure.getClass().getSimpleName());
		} else {
			log.atWarn().log("A storage repair round failed: {}", failure.getClass().getSimpleName());
		}
	}

	@PreDestroy
	private void stop() {
		executor.shutdownNow();
	}
}
