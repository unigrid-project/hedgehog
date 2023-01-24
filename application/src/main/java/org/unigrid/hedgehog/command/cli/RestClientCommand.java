/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.command.cli;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;

@RequiredArgsConstructor
class RestClientCommand<T> implements Runnable {
	private T entity;
	private final String method;
	private final String location;

	RestClientCommand(T entity, String method, String location) {
		this(method, location);
		this.entity = entity;
	}

	@Override
	public void run() {
		try (RestClient rest = new RestClient(RestOptions.getHost(), RestOptions.getPort(), true)) {
			switch (method) {
				case HttpMethod.GET:
					get(rest.get(location));
					break;

				case HttpMethod.POST:
					post(rest.post(location, entity));
					break;

				default:
					throw new UnsupportedOperationException();
			}

		} catch (ResponseOddityException ex) {
			System.err.println(ex.getMessage());
		}
	}

	protected void get(Response response) {
		throw new UnsupportedOperationException();
	}

	protected void post(Response response) {
		throw new UnsupportedOperationException();
	}
}
