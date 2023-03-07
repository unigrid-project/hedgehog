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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.Quic;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import io.netty.util.CharsetUtil;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.Cleanup;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.Hedgehog;

@ApplicationScoped
public class EncryptedTokenHandler implements QuicTokenHandler {
	private static final String SERVER_NAME = Hedgehog.class.getSimpleName();
	private static final int IPV6_LENGTH = 16;
	private static final int QUICHE_MAX_CONN_ID_LEN = 18;

	@Inject
	private UUID uuId;

	@PostConstruct
	private void init() {
		Quic.ensureAvailability();
	}

	@SneakyThrows
	private SecretKey getUniqueKey() {
		return new SecretKeySpec(uuId.toString().replace("-", "")
			.getBytes(CharsetUtil.ISO_8859_1), "AES");
	}

	@Override
	public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
		@Cleanup("release") final ByteBuf tmp = Unpooled.buffer();

		tmp.writeBytes(SERVER_NAME.getBytes(CharsetUtil.ISO_8859_1))
			.writeBytes(address.getAddress().getAddress());
		try {
			final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, getUniqueKey(), new IvParameterSpec(new byte[16]));

			final byte[] encoded = cipher.doFinal(tmp.array(), 0, tmp.writerIndex());

			out.writeByte(encoded.length).writeBytes(encoded)
				.writeBytes(dcid, dcid.readerIndex(), dcid.readableBytes());

		} catch (BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException
			| InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException ex) {

			System.err.println(ex);
			return false;
		}

		return true;
	}

	@Override
	public int validateToken(ByteBuf token, InetSocketAddress address) {
		byte[] tmp = ByteBufUtil.getBytes(token);
		final int length = tmp[0];

		try {
			final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

			cipher.init(Cipher.DECRYPT_MODE, getUniqueKey(), new IvParameterSpec(new byte[16]));
			tmp = cipher.doFinal(tmp, 1, length);

		} catch (BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException
			| InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException ex) {

			System.err.println(ex);
			return -1;
		}

		final String tokenServer = new String(tmp, 0, SERVER_NAME.length(), CharsetUtil.ISO_8859_1);
		final int tokenAddressLength = tmp.length - SERVER_NAME.length();

		final byte[] tokenAddress = Arrays.copyOfRange(tmp, SERVER_NAME.length(),
			SERVER_NAME.length() + tokenAddressLength
		);

		if (tmp.length < SERVER_NAME.length() + address.getAddress().getAddress().length) {
			return -1;
		} else if (!SERVER_NAME.equals(tokenServer)) {
			return -1;
		} else if (!Arrays.equals(address.getAddress().getAddress(), tokenAddress)) {
			return -1;
		}

		return length + 1; /* One extra byte stores the length */
	}

	@Override
	public int maxTokenLength() {
		final int length = SERVER_NAME.length() + IPV6_LENGTH;

		/* AES-encoded (PKCS5) data grows in 16-byte increments */
		return length + (16 - (length % 16)) +  QUICHE_MAX_CONN_ID_LEN;
	}
}
