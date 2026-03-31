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

	package org.unigrid.hedgehog.model;

	import com.fasterxml.jackson.core.JsonProcessingException;
	import com.fasterxml.jackson.databind.ObjectMapper;
	import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
	
	/**
	 * Central JSON utility baserad på Jackson.
	 *
	 * - Serialisering: Object -> JSON
	 * - Deserialisering: JSON -> Object
	 */
	public final class Json {
	
		private static final ObjectMapper MAPPER = new ObjectMapper()
				.registerModule(new JavaTimeModule());
	
		private Json() {
			// utility class
		}
	
		/**
		 * Serialisera objekt till JSON-sträng.
		 */
		public static String parse(Object object) {
			try {
				return MAPPER.writeValueAsString(object);
			} catch (JsonProcessingException e) {
				throw new IllegalStateException("Failed to serialize object to JSON", e);
			}
		}
	
		/**
		 * Deserialisera JSON-sträng till objekt.
		 */
		public static <T> T parse(String json, Class<T> type) {
			try {
				return MAPPER.readValue(json, type);
			} catch (Exception e) {
				throw new IllegalStateException(
						"Failed to deserialize JSON to " + type.getSimpleName(), e);
			}
		}
	}
	