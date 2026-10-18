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

package org.unigrid.hedgehog.s3.resource;

import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class CopySourceTest {
	@Example
	public void decodesTheBucketKeyAndVersion() {
		assertThat(CopySource.parse("photos/a%20b%2Bc%E2%82%AC"), equalTo(new CopySource("photos", "a b+c€", null)));
		assertThat(CopySource.parse("/photos/dir/key?versionId=0000000000000001"),
			equalTo(new CopySource("photos", "dir/key", "0000000000000001"))
		);
		assertThat(CopySource.parse("photos/a+b%3Fc?x-id=CopyObject"), equalTo(new CopySource("photos", "a+b?c", null)));
	}

	@Example
	public void refusesASourceWithoutBothBucketAndKey() {
		for (String header : new String[] { "photos", "/photos", "photos/", "/key", "photos/%zz" }) {
			assertThat(assertThrows(S3Exception.class, () -> CopySource.parse(header)).getCode(),
				equalTo(S3ErrorCode.InvalidArgument)
			);
		}
	}
}
