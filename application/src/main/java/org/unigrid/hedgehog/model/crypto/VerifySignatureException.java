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

package org.unigrid.hedgehog.model.crypto;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Objects;
import java.util.function.Supplier;

public class VerifySignatureException extends Exception {
	private String message;
	private transient Supplier<String> pending;

	public VerifySignatureException(String msg, Throwable cause) {
		super(msg, cause);
	}

	/* For a failure that anyone can cause as often as they like, such as a signature that is no signature, which is
	   tried against every key of the network: the text is built the first time it is read, and a node that logs no
	   trace never reads it. */
	public VerifySignatureException(Supplier<String> msg, Throwable cause) {
		super(null, cause);
		pending = msg;
	}

	@Override
	public synchronized String getMessage() {
		if (Objects.nonNull(pending)) {
			message = pending.get();
			pending = null;
		}

		return Objects.isNull(message) ? super.getMessage() : message;
	}

	private void writeObject(ObjectOutputStream stream) throws IOException {
		getMessage();
		stream.defaultWriteObject();
	}
}
