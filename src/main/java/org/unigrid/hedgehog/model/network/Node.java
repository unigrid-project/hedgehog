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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.Data;
import org.unigrid.hedgehog.model.network.packet.Ping;

@Data
@Builder
public class Node {
	private InetAddress address;
	private Optional<Channel> channel;
	private Ping ping;
	private Instant lastPingTime;
	@Builder.Default private boolean connected = true;
	@Builder.Default private Details details = new Details();

	@Data
	private static class Details {
		private String[] protocols;
		private int version;
	}
}
