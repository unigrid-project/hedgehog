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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.apache.commons.configuration2.sync.LockMode;
import org.unigrid.hedgehog.model.cdi.Lock;
import org.unigrid.hedgehog.model.cdi.Protected;

@ApplicationScoped
public class ChannelMap {
	private Map<Channel, Node> channels;

	@PostConstruct
	private void init() {
		channels = new HashMap<>();
	}

	@Protected @Lock(LockMode.WRITE)
	public void clear() {
		channels.clear();
	}

	@Protected @Lock(LockMode.WRITE)
	public void modify(Channel channel, BiConsumer<Channel, Node> consumer) {
		final Node node = channels.get(channel);

		if (Objects.nonNull(node)) {
			consumer.accept(channel, node);
		}
	}

	@Protected @Lock(LockMode.READ)
	public Optional<Node> get(Channel channel) {
		final Node node = channels.get(channel);

		if (Objects.nonNull(node)) {
			return Optional.of(node);
		}

		return Optional.empty();
	}

	@Protected @Lock(LockMode.WRITE)
	public void set(Channel channel, Node node) {
		channels.put(channel, node);
	}

	@Protected @Lock(LockMode.WRITE)
	public void remove(Channel channel) {
		channels.remove(channel);
	}
}
