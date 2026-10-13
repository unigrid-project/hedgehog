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

package org.unigrid.hedgehog.model.network.packet;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;

@Data
@EqualsAndHashCode(callSuper = false)
public class JoinReply extends Packet implements Correlated {
	public enum Status {
		JOINED, FULL, DISABLED, UNKNOWN_CHAIN, ERROR;

		/* A status byte from a peer is untrusted, and one this node does not know is no better than a failure */
		public static Status of(final int ordinal) {
			final Status[] statuses = values();
			return ordinal >= 0 && ordinal < statuses.length ? statuses[ordinal] : ERROR;
		}
	}

	private long requestId;
	private Status status;

	/* Present when joined, together with the heads the joining writer anchors the other feeds at */
	@Getter(AccessLevel.NONE) private ChainId chainId;
	private Map<WriterId, Head> heads;

	public JoinReply() {
		setType(Type.JOIN_REPLY);
	}

	@Builder
	public JoinReply(final long requestId, final Status status, final ChainId chainId,
		final Map<WriterId, Head> heads) {

		this();
		this.requestId = requestId;
		this.status = status;
		this.chainId = chainId;
		this.heads = Objects.requireNonNullElse(heads, Map.of());
	}

	public Optional<ChainId> getChainId() {
		return Optional.ofNullable(chainId);
	}
}
