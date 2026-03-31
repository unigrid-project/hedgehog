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

    package org.unigrid.hedgehog.client;

    import java.io.Serial;
    import java.util.Objects;
    
    /**
     * Exception som används när ett oväntat HTTP-response status inträffar.
     *
     * Denna version är självständig och kräver inga externa Jakarta / JAX-RS beroenden.
     */
    public final class ResponseOddityException extends Exception {
    
        @Serial
        private static final long serialVersionUID = 1L;
    
        private final int statusCode;
        private final String reason;
    
        /**
         * Skapar ett nytt undantag baserat på HTTP statuskod och en beskrivning.
         *
         * @param statusCode HTTP-statuskod
         * @param reason     Beskrivning av status (t.ex. "Not Found")
         */
        public ResponseOddityException(int statusCode, String reason) {
            super(formatStatus(statusCode, reason));
            this.statusCode = statusCode;
            this.reason = Objects.requireNonNullElse(reason, "No reason provided");
        }
    
        private static String formatStatus(int code, String reason) {
            return String.format("%d (%s)", code, Objects.requireNonNullElse(reason, "No reason provided"));
        }
    
        public int getStatusCode() {
            return statusCode;
        }
    
        public String getReason() {
            return reason;
        }
    }
    