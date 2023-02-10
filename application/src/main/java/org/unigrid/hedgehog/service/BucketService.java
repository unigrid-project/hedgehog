/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import org.apache.commons.lang3.RandomStringUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.filesystem.VirtualAbstractPath;
import org.unigrid.hedgehog.filesystem.VirtualDirectory;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.Owner;

@Data
@ApplicationScoped
public class BucketService {
	@Inject
	private ApplicationDirectory applicationDirectory;

	private VirtualDirectory dataDir;

	@PostConstruct
	private void init() {
		dataDir = new VirtualDirectory<>("/", "s3data", () -> applicationDirectory.getUserDataDir().toString());
	}

	public String create(String name) {
		if (dataDir.contains(name)) {
			System.out.println(name + " already exists");
		} else {
			dataDir.addChild(new VirtualDirectory<>("/", name, () -> dataDir.getRootPathSupplier().get().toString()));
			System.out.println(name + " was created");
		}

		Optional<VirtualAbstractPath> bucket = dataDir.find(name);

		String location = "";
		if (bucket.isPresent()) {
			location = bucket.get().getPath().toString() + "/" + name;
		}

		return location;
	}

	public ListAllMyBucketsResult listBuckets() {
		final ArrayList<Bucket> buckets = new ArrayList<>();

		for (VirtualAbstractPath mp : (List<VirtualAbstractPath>) dataDir.getChildren()) {
			buckets.add(new Bucket(new Date().toInstant(), mp.getName()));
		}

		return new ListAllMyBucketsResult(buckets, new Owner("user", RandomStringUtils.randomNumeric(20)));
	}

	public boolean delete(String bucketName) throws IOException {
		Optional<VirtualAbstractPath> customDir = dataDir.find(bucketName);

		if (dataDir.contains(bucketName) && customDir.isPresent()) {
			dataDir.deleteChild(bucketName);
			return true;
		}

		return false;
	}

}
