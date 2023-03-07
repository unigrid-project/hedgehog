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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Optional;
import lombok.Builder;
import lombok.Data;
import org.unigrid.hedgehog.model.network.packet.Ping;

@Data
@Builder
public class Node {
	private InetSocketAddress address;
	@Builder.Default private Optional<Channel> channel = Optional.empty();
	@Builder.Default private Details details = new Details();
	private Instant lastPingTime;
	@Builder.Default private Optional<Ping> ping = Optional.empty();

	@Data
	public static class Details {
		private String[] protocols;
		private int version;
	}
}
