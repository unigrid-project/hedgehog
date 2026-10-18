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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.model.NodeStatus.Activity;
import org.unigrid.hedgehog.model.NodeStatus.Phase;

public class NodeStatusTest {
	private final NodeStatus status = new NodeStatus();

	@Example
	public void shouldStartRunningWithoutAMessage() {
		assertThat(status.current(), equalTo(new Phase(Activity.RUNNING, NodeStatus.COMPLETE, null)));
	}

	@Example
	public void shouldCarryTheMessageOfAFailure() {
		status.failed("connection refused");
		assertThat(status.current(), equalTo(new Phase(Activity.FAILED, null, "connection refused")));
	}

	@Example
	public void shouldStayFailedUntilTheNextDownloadStarts() {
		status.failed("connection refused");
		status.downloading(null);
		assertThat(status.current(), equalTo(new Phase(Activity.DOWNLOADING, null, null)));
	}

	@Example
	public void shouldRunAgainWithoutTheMessageOfAnEarlierFailure() {
		status.failed("connection refused");
		status.running();
		assertThat(status.current(), equalTo(new Phase(Activity.RUNNING, NodeStatus.COMPLETE, null)));
	}
}
