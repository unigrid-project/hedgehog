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

package org.unigrid.hedgehog.server.rest;

import jakarta.ws.rs.core.Response;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.crypto.Signable;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class ResourceHelper {
	public static <S extends Serializable> S getNewOrClonedSporkSection(Supplier<S> supplier, Supplier<S> newSupplier) {
		S section = supplier.get();

		if (Objects.isNull(section)) {
			section = newSupplier.get();
		} else {
			section = SerializationUtils.clone(section);
		}

		return section;
	}

	public static <S extends Signable> Response commitAndSign(S signable, String privateKey, SporkDatabase sporkDatabase,
		boolean isUpdate, Consumer<S> consumer) {

		try {
			signable.sign(privateKey);
		} catch (SigningException ex) {
			/* As we clone() the vesting storage, returning here results in a database NOP */
			return Response.status(Response.Status.UNAUTHORIZED).entity(ex).build();
		}

		consumer.accept(signable);

		if (isUpdate) {
			return Response.noContent().build();
		}

		return Response.ok().build();
	}
}
