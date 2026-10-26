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

package org.unigrid.hedgehog.s3.error;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/* Thrown for ordinary outcomes such as a missing key, so it carries no stack trace */
@Getter
public class S3Exception extends RuntimeException {
	private final S3ErrorCode code;
	private final Map<String, Object> headers = new LinkedHashMap<>();

	public S3Exception(final S3ErrorCode code) {
		this(code, code.getMessage());
	}

	public S3Exception(final S3ErrorCode code, final String message) {
		this(code, message, null);
	}

	public S3Exception(final S3ErrorCode code, final String message, final Throwable cause) {
		super(message, cause, false, false);
		this.code = code;
	}

	/* Some refusals still describe what was found, such as the delete marker that hides a key */
	public S3Exception header(final String name, final Object value) {
		headers.put(name, value);
		return this;
	}
}
