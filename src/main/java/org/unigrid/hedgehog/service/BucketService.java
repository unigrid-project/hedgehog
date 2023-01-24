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

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.Owner;

public class BucketService {
	public final String dataDir = System.getProperty("user.home") + File.separator + "s3data";

	public String create(String name) {
		File customDir;
		String location = "";

		try {
			customDir = new File(dataDir + File.separator + name);

			if (customDir.exists()) {
				System.out.println(customDir + " already exists");
			} else if (customDir.mkdirs()) {
				System.out.println(customDir + " was created");
			} else {
				System.out.println(customDir + " was not created");
			}

			location = customDir.getName();
		} catch (Exception e) {
			e.printStackTrace();
		}

		return location;
	}

	public ListAllMyBucketsResult listBuckets() {
		List<String> directories = Stream.of(new File(dataDir).listFiles())
			.filter(file -> file.isDirectory())
			.map(File::getName)
			.collect(Collectors.toList());

		ArrayList<Bucket> buckets = new ArrayList<>();

		for (String directory : directories) {
			buckets.add(new Bucket(new Date().toInstant(), directory));
		}

		return new ListAllMyBucketsResult(buckets, new Owner("user", RandomStringUtils.randomNumeric(20)));
	}


	public boolean delete(String bucketName) {
		File customDir = new File(dataDir + File.separator + bucketName);

		if (!customDir.exists()) {
			return false;
		}

		return customDir.delete();
	}

}
