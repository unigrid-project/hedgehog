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
	 package org.unigrid.hedgehog.model.crypto;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Hanterar nätverksnycklar och signering/validering.
 */
public class NetworkKey {

    // Enkel stub för NetOptions
    private static final NetOptions netOptions = new NetOptions();

    /**
     * Hämtar publika nätverksnycklar.
     */
    public static String[] getPublicKeys() {
        return netOptions.getNetworkKeys();
    }

    /**
     * Kontrollera om privatnyckeln är betrodd.
     */
    public static boolean isTrusted(String privateKey) {
        try {
            return RandomSignableData.create(privateKey).isValidSignature();
        } catch (SigningException ex) {
            ex.printStackTrace();
            return false;
        }
    }

    // ===================== INNER CLASS =====================

    private static class RandomSignableData implements Signable {

        private static final int SIZE = 32;

        private byte[] signable;
        private byte[] signature;

        private RandomSignableData() {
        }

        /**
         * Factory method.
         */
        public static RandomSignableData create(String privateKeyHex) throws SigningException {
            RandomSignableData data = new RandomSignableData();
            data.sign(privateKeyHex);
            return data;
        }

        @Override
        public boolean isValidSignature() {
            try {
                for (String key : getPublicKeys()) {
                    if (Signature.verify(this, key)) {
                        return true;
                    }
                }
            } catch (VerifySignatureException ex) {
                ex.printStackTrace();
            }
            return false;
        }

        @Override
        public void sign(String privateKeyHex) throws SigningException {

            signable = new byte[SIZE];
            new SecureRandom().nextBytes(signable);

            Signature sig = new Signature(Optional.of(privateKeyHex), Optional.empty());
            this.signature = sig.sign(signable);
        }

        @Override
        public byte[] getSignable() {
            return signable;
        }

        @Override
        public byte[] getSignature() {
            return signature;
        }
    }

    // ===================== SIMPLE STUBS =====================

    public static class NetOptions {

        public String[] getNetworkKeys() {
            return new String[]{
                "publicKey1",
                "publicKey2",
                "publicKey3"
            };
        }
    }

    public interface Signable {

        boolean isValidSignature();

        void sign(String privateKeyHex) throws SigningException;

        byte[] getSignable();

        byte[] getSignature();
    }

    public static class Signature {

        private final Optional<String> privateKey;
        private final Optional<String> publicKey;

        public Signature(Optional<String> privateKey, Optional<String> publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        public byte[] sign(byte[] data) {
            // Enkel stub: returnerar samma data som "signatur"
            return data;
        }

        public static boolean verify(Signable signable, String publicKey)
                throws VerifySignatureException {
            // Enkel stub: alla signaturer anses giltiga
            return true;
        }
    }

    public static class SigningException extends Exception {

        public SigningException(String message, Throwable cause) {
            super(message, cause);
        }

        public SigningException(String message) {
            super(message);
        }
    }

    public static class VerifySignatureException extends Exception {

        public VerifySignatureException(String message) {
            super(message);
        }
    }
}