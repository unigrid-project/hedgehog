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

package org.unigrid.hedgehog.proxy.socks;

/* The front end's handle on one stream of a tunnel */
public interface TunnelStream {
	/* Queues bytes towards the destination and never refuses them. The front end stops reading from its
	   client while isWritable is false, so at most one more read of its client arrives after that. */
	void write(byte[] bytes);

	boolean isWritable();

	/* False holds back what the destination sends while the SOCKS client cannot take more, true lets it flow */
	void setReadable(boolean readable);

	/* Ends the stream from the client's side, if the tunnel has not ended it already; the listener hears
	   nothing more */
	void close();
}
