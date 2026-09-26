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

package org.unigrid.hedgehog.model;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicReference;

/**
* What the node is busy with. Activity and progress change together, so a reader never sees the
* progress of one activity reported against another.
*/
@ApplicationScoped
public class NodeStatus {
	public static final int COMPLETE = 100;

	private final AtomicReference<Phase> phase = new AtomicReference<>(new Phase(Activity.RUNNING, COMPLETE));

	public Phase current() {
		return phase.get();
	}

	/* The progress is a percentage, or null while the size of the download is unknown. */
	public void downloading(Integer progress) {
		phase.set(new Phase(Activity.DOWNLOADING, progress));
	}

	public void running() {
		phase.set(new Phase(Activity.RUNNING, COMPLETE));
	}

	public enum Activity {
		DOWNLOADING, RUNNING
	}

	public record Phase(Activity activity, Integer progress) {
	}
}
