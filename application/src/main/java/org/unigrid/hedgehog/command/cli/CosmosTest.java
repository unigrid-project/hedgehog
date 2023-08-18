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

package org.unigrid.hedgehog.command.cli;

import jakarta.ws.rs.core.Response;
import java.util.Properties;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;
import picocli.CommandLine;

@CommandLine.Command(name = "cosmos-test")
public class CosmosTest implements Runnable {

	@Override
	@SneakyThrows
	public void run() {
		Properties props = System.getProperties();
		props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());

		final RestClient client = new RestClient(RestOptions.getHost(), 1317, true);

		try {
			Response res = client.get("/cosmos-spork/cosmosspork/params");
			System.out.println(res.getStatus());
			System.out.println(res.getEntity());
			System.out.println("1111");
		} catch (ResponseOddityException ex) {
			ex.printStackTrace();
		}

		client.close();
	}
}
