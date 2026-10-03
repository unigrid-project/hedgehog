/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

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

package org.unigrid.hedgehog.model.network.packet;

import java.io.Serializable;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.storage.StorageStatus;

@Data
@EqualsAndHashCode(callSuper = false)
public class FragmentReply extends Packet implements Correlated, Serializable {
	private long requestId;
	private StorageStatus status;
	private byte[] fragment;

	public FragmentReply() {
		setType(Type.FRAGMENT_REPLY);
	}

	@Builder
	public FragmentReply(final long requestId, final StorageStatus status, final byte[] fragment) {
		this();
		this.requestId = requestId;
		this.status = status;
		this.fragment = fragment;
	}
}
