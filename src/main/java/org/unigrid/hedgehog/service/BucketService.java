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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.Owner;

public class BucketService {
	public ArrayList<Bucket> buckets = new ArrayList(
		Arrays.asList(
			new Bucket(new Date().toString(), "test0"),
			new Bucket(new Date().toString(), "test1"),
			new Bucket(new Date().toString(), "test2")
		)
	);

	public Owner owner = new Owner("Degen", "11111");

	public ListAllMyBucketsResult getAll() {
		return new ListAllMyBucketsResult(buckets, owner);
	}

	public Bucket create(String name) {
		Bucket bucket = new Bucket(new Date().toString(), name);
		buckets.add(bucket);

		return bucket;
	}

	public boolean delete(String bucketName) {
		return buckets.removeIf(obj -> obj.name.equals(bucketName));
	}

}
