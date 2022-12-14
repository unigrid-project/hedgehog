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

package org.unigrid.hedgehog.service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import org.unigrid.hedgehog.model.s3.entity.Content;
import org.unigrid.hedgehog.model.s3.entity.CopyObjectResult;
import org.unigrid.hedgehog.model.s3.entity.ListBucketResult;
import org.unigrid.hedgehog.model.s3.entity.Owner;

public class ObjectService {
	public List<Content> objects = new ArrayList<>(Arrays.asList(
		new Content("key1", new Date().toString(), "eTag1", 1,
			"storageClass1", new Owner("name1", "1")),
		new Content("key2", new Date().toString(), "eTag2", 2,
			"storageClass2", new Owner("name2", "2")),
		new Content("key3", new Date().toString(), "eTag3", 3,
			"storageClass3", new Owner("name3", "3"))
	)
	);

	public ListBucketResult listBucketResults = new ListBucketResult("name1", "prefix1", "marker1",
		100, true, objects);

	public boolean put(InputStream data) throws IOException {
		InputStreamReader inputStreamReader = new InputStreamReader(data);
		BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

		String line;
		while ((line = bufferedReader.readLine()) != null) {
			System.out.println(line);
		}

		return data != null;
	}

	public ListBucketResult getAll() {
		return listBucketResults;
	}

	public CopyObjectResult copy(String bucket, String key, String copySource) {
		return new CopyObjectResult("eTag", Instant.now().toString(), "checksum1",
			"checksum2", "checksum3", "checksum4");
	}

	public byte[] download(String name) throws Exception {
		List<Content> result = objects.stream()
			.filter(obj -> obj.key.equals(name))
			.collect(Collectors.toList());

		if (result.isEmpty()) {
			throw new Exception("No data");
		}

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(bos);
		oos.writeObject(result);

		return bos.toByteArray();
	}

	public boolean delete(String objectName) {
		return objects.removeIf(obj -> obj.key.equals(objectName));
	}
}
