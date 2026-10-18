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

package org.unigrid.hedgehog.command.s3;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.server.s3.S3Credentials;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "delete", description = "Delete an access key, after which nothing signed with it is accepted.")
public class S3KeyDelete implements Callable<Integer> {
	@Parameters(index = "0", paramLabel = "<access key id>", description = "Id of the access key to delete.")
	private String accessKeyId;

	@Override
	public Integer call() {
		try {
			if (new S3Credentials(S3Credentials.getFile()).delete(accessKeyId)) {
				return 0;
			}

			System.err.println("No access key " + accessKeyId);
			return 1;
		} catch (IOException ex) {
			System.err.println("Failed to delete the access key: " + ex.getMessage());
			return 2;
		}
	}
}
