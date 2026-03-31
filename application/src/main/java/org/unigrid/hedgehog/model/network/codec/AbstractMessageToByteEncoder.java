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
	package org.unigrid.hedgehog.model.network.codec;

	import io.netty.buffer.ByteBuf;
	import io.netty.channel.ChannelHandlerContext;
	import io.netty.handler.codec.MessageToByteEncoder;
	
	import java.lang.reflect.Field;
	import java.lang.reflect.Method;
	import java.util.Optional;
	
	import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
	import org.unigrid.hedgehog.model.network.packet.Packet;
	
	/**
	 * Bas-klass för alla Netty-encoders som producerar Packet-objekt till ByteBuf.
	 *
	 * @param <T> typen av Packet som encodern hanterar
	 */
	public abstract class AbstractMessageToByteEncoder<T extends Packet>
			extends MessageToByteEncoder<T>
			implements PacketEncoder<T> {
	
		/**
		 * Skriver frame-headern innan paketdata.
		 */
		private void writeFrameHeader(ChannelHandlerContext ctx, ByteBuf out, int len) throws Exception {
			out.writeShort(FrameDecoder.MAGIC);
			out.writeShort(resolvePacketTypeValue());
			out.writeInt(len);
		}
	
		/**
		 * Encodar Packet till Optional ByteBuf.
		 */
		public abstract Optional<ByteBuf> encode(ChannelHandlerContext ctx, T in) throws Exception;
	
		/**
		 * Netty entrypoint
		 *
		 * MÅSTE vara public för att matcha PacketEncoder-interfacet.
		 */
		@Override
		public final void encode(ChannelHandlerContext ctx, T in, ByteBuf out) throws Exception {
			Optional<ByteBuf> opt = encode(ctx, in);
	
			if (opt.isPresent()) {
				ByteBuf data = opt.get();
				try {
					writeFrameHeader(ctx, out, data.readableBytes());
					out.writeBytes(data, data.readerIndex(), data.readableBytes());
				} finally {
					data.release();
				}
			}
		}
	
		/**
		 * Returnerar Packet.Type för denna encoder.
		 */
		@Override
		public abstract Packet.Type getCodecType();
	
		/**
		 * Löser Packet.Type → short
		 *
		 * VIKTIGT:
		 * - Använder INTE direktanrop till getValue() (finns inte i din typ)
		 * - Försöker först method via reflection
		 * - Fallback till field "value"
		 */
		private short resolvePacketTypeValue() throws Exception {
			Packet.Type type = getCodecType();
	
			// 1. Försök hitta getValue() via reflection (utan compile-beroende)
			try {
				Method m = type.getClass().getMethod("getValue");
				Object v = m.invoke(type);
				return ((Number) v).shortValue();
			} catch (NoSuchMethodException ignored) {
				// fortsätt
			}
	
			// 2. Fallback till field "value"
			Field f = type.getClass().getDeclaredField("value");
			f.setAccessible(true);
			return ((Number) f.get(type)).shortValue();
		}
	}
	