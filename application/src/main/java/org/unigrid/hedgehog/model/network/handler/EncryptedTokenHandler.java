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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.incubator.codec.quic.Quic;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import io.netty.util.CharsetUtil;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;

/* A retry token is a byte with the length of what follows, the server name and the address of the client encrypted
   under the key of this node, and the connection id the client first used. Whoever sends a token is unknown, as the
   token is how the server learns that the address is real, so a token of any bytes is refused and never throws. */
@Slf4j
@ApplicationScoped
public class EncryptedTokenHandler implements QuicTokenHandler {
	/* Part of the token wire format, so it must not follow a rename of the main class */
	private static final String SERVER_NAME = "Hedgehog";
	private static final int IPV6_LENGTH = 16;
	private static final int QUICHE_MAX_CONN_ID_LEN = 18;
	private static final int AES_BLOCK = 16;
	private static final int INVALID = -1;

	@Inject
	private UUID uuId;

	public EncryptedTokenHandler() {
		/* For the container, which fills in what is injected */
	}

	EncryptedTokenHandler(UUID uuId) {
		this.uuId = uuId;
	}

	@PostConstruct
	private void init() {
		Quic.ensureAvailability();
	}

	private SecretKey getUniqueKey() {
		return new SecretKeySpec(uuId.toString().replace("-", "")
			.getBytes(CharsetUtil.ISO_8859_1), "AES");
	}

	private Cipher cipher(int mode) throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");

		cipher.init(mode, getUniqueKey(), new IvParameterSpec(new byte[AES_BLOCK]));
		return cipher;
	}

	private static byte[] plainTokenOf(InetSocketAddress address) {
		return ArrayUtils.addAll(SERVER_NAME.getBytes(CharsetUtil.ISO_8859_1), address.getAddress().getAddress());
	}

	@Override
	public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
		try {
			final byte[] encoded = cipher(Cipher.ENCRYPT_MODE).doFinal(plainTokenOf(address));

			out.writeByte(encoded.length).writeBytes(encoded)
				.writeBytes(dcid, dcid.readerIndex(), dcid.readableBytes());

		} catch (GeneralSecurityException ex) {
			log.atWarn().setCause(ex).log("Could not write a retry token");
			return false;
		}

		return true;
	}

	/* The offset of the connection id in a token made here for this address, else -1 */
	@Override
	public int validateToken(ByteBuf token, InetSocketAddress address) {
		final byte[] bytes = ByteBufUtil.getBytes(token);
		final int length = bytes.length == 0 ? 0 : Byte.toUnsignedInt(bytes[0]);

		if (length == 0 || length > bytes.length - 1) {
			return INVALID;
		}

		try {
			final byte[] plain = cipher(Cipher.DECRYPT_MODE).doFinal(bytes, 1, length);

			return MessageDigest.isEqual(plain, plainTokenOf(address)) ? length + 1 : INVALID;
		} catch (GeneralSecurityException ex) {
			log.atDebug().addArgument(address).log("A retry token from {} that does not decrypt");
			return INVALID;
		}
	}

	/* The length byte, the server name and an IPv6 address padded to whole AES blocks, and the longest connection id */
	@Override
	public int maxTokenLength() {
		final int length = SERVER_NAME.length() + IPV6_LENGTH;

		return 1 + length + (AES_BLOCK - length % AES_BLOCK) + QUICHE_MAX_CONN_ID_LEN;
	}
}
