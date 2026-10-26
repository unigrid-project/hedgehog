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

package org.unigrid.hedgehog.s3.server;

import io.netty.channel.Channel;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/* Reads a channel only while nothing holds it back, such as a full request body or a response still being sent */
@RequiredArgsConstructor
final class S3ReadGate {
	private final Channel channel;
	private final Set<Object> holders = new HashSet<>();

	synchronized void hold(final Object holder) {
		holders.add(holder);
		channel.config().setAutoRead(false);
	}

	synchronized void release(final Object holder) {
		if (holders.remove(holder) && holders.isEmpty()) {
			channel.config().setAutoRead(true);
		}
	}
}
