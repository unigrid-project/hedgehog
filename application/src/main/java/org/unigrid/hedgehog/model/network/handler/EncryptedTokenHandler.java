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
import io.netty.buffer.Unpooled;
import io.netty.incubator.codec.quic.Quic;
import io.netty.incubator.codec.quic.QuicTokenHandler;
import io.netty.util.CharsetUtil;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.unigrid.hedgehog.Hedgehog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.util.UUID;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@ApplicationScoped
public class EncryptedTokenHandler implements QuicTokenHandler {

    private static final Logger log = LoggerFactory.getLogger(EncryptedTokenHandler.class);
    private static final String SERVER_NAME = Hedgehog.class.getSimpleName();

    @Inject
    private UUID uuId;

    @PostConstruct
    private void init() {
        Quic.ensureAvailability();
    }

    private SecretKey getUniqueKey() {
        return new SecretKeySpec(uuId.toString().replace("-", "").getBytes(CharsetUtil.ISO_8859_1), "AES");
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        final ByteBuf tmp = Unpooled.buffer();
        try {
            tmp.writeBytes(SERVER_NAME.getBytes(CharsetUtil.ISO_8859_1));
            tmp.writeBytes(address.getAddress().getAddress());

            final Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, getUniqueKey(), new IvParameterSpec(new byte[16]));
            final byte[] encoded = cipher.doFinal(tmp.array(), 0, tmp.writerIndex());

            out.writeByte(encoded.length)
               .writeBytes(encoded)
               .writeBytes(dcid, dcid.readerIndex(), dcid.readableBytes());

            return true;
        } catch (BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException
                | InvalidKeyException | NoSuchAlgorithmException | NoSuchPaddingException ex) {
            log.error("Encryption error: {}", ex.getMessage(), ex);
            return false;
        } finally {
            tmp.release();
        }
    }

    // Rätt returtyp enligt senaste Netty QuicTokenHandler
    @Override
    public int validateToken(ByteBuf token, InetSocketAddress remoteAddress) {
        // Returnerar 0 om ogiltigt, eller längden på token om giltigt
        if (token == null || token.readableBytes() == 0) return 0;
        return token.readableBytes();
    }

    @Override
    public int maxTokenLength() {
        return 512; // eller valfri maxstorlek
    }
}