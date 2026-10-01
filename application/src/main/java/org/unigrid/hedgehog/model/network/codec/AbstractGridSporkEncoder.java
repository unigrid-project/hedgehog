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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.spork.SporkContentEncoder;

@Slf4j
public abstract class AbstractGridSporkEncoder<T extends Packet> extends AbstractMessageToByteEncoder<T> {

	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [  spork type  ][    flags     ][           reserved           ]
            [                           timestamp                          ]
	    [                      previous timpestamp                     ]
	    [                           reserved                           ]
	    [                       << spork data >>                       ]
	    [                    << spork delta data >>                    ]
	    [     size     ][             signature (size long)          >>]
	    [     size     ][   cosignature (size long, 0 while pending) >>]
	    [      signature log entries       ][   << log entries >>      >>]

	    Signature log entry:
	    [                    signed version timestamp                  ]
	    [     size     ][            signer public key (size long)   >>]
	    [                    << SHA-512 digest (64 bytes) >>           ]
	    [     size     ][             signature (size long)          >>]
	    [     size     ][   cosigner public key (size long, 0 if none) >>]
	    [     size     ][       cosignature (size long, 0 if none)   >>]
	*/
	public void encodeGridSpork(ChannelHandlerContext ctx, GridSpork spork, ByteBuf out) throws Exception {
		log.atTrace().log("encoding spork chunk of type {}", spork.getType());

		if (SporkContentEncoder.canEncode(spork.getType())) {
			@Cleanup("release")
			final ByteBuf data = Unpooled.buffer();

			SporkContentEncoder.encode(spork, data);

			writeSized(data, spork.getSignature());
			writeSized(data, spork.isPending() ? new byte[0] : spork.getCosignature());

			data.writeInt(spork.getSignatureLog().size());

			spork.getSignatureLog().getEntries().forEach(entry -> {
				data.writeBytes(entry.toBytes());

				/* The hashed layout leaves out an absent cosigner, the wire marks it with two empty sizes */
				if (!entry.isCosigned()) {
					data.writeZero(2 * Short.BYTES);
				}
			});
			out.writeBytes(data);
		}
	}

	private static void writeSized(ByteBuf data, byte[] bytes) {
		data.writeShort(bytes.length);
		data.writeBytes(bytes);
	}
}
