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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.codec.digest.DigestUtils;
import org.unigrid.hedgehog.model.s3.entity.Content;
import org.unigrid.hedgehog.model.s3.entity.CopyObjectResult;
import org.unigrid.hedgehog.model.s3.entity.ListBucketResult;
import org.unigrid.hedgehog.model.s3.entity.NoSuchBucketException;
import org.unigrid.hedgehog.model.s3.entity.NoSuchKeyException;

public class ObjectService {
	public String dataDir = System.getProperty("user.home") + File.separator + "s3data";
	public static final int MAX_KEYS = 10000;

	public void put(String bucket, String key, InputStream data) throws IOException, NoSuchBucketException {
		File bucketFile = new File(dataDir + File.separator + bucket);

		if (!bucketFile.exists()) {
			throw new NoSuchBucketException("No such bucket");
		}

		File file = new File(dataDir + File.separator + bucket + File.separator + key);
		Files.copy(data, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	public ListBucketResult listBucket(String bucket, Optional<String> prefix, Optional<String> delimiter,
		Optional<Integer> maxkeys) throws NoSuchBucketException {
		File bucketFile = new File(dataDir + File.separator + bucket);

		if (!bucketFile.exists()) {
			throw new NoSuchBucketException(bucket);
		}

		String prefixNoLeadingSlash = prefix.orElse("").replaceFirst("^/+", "");
		String bucketFileString = fromOs(bucketFile.toString());
		List<File> bucketFiles = Arrays.stream(bucketFile.listFiles())
			.filter(f -> {
				String fString = fromOs(f.toString())
					.substring(bucketFileString.length())
					.replaceFirst("^/+", "");
				return fString.startsWith(prefixNoLeadingSlash) && !f.isDirectory();
			})
			.collect(Collectors.toList());

		List<Content> files = bucketFiles.stream()
			.map(f -> {
				try ( FileInputStream stream = new FileInputStream(f)) {
					String checksum = DigestUtils.md5Hex(stream);

					return new Content(fromOs(f.toString())
						.substring(bucketFileString.length() + 1).replaceFirst("^/+", ""),
						new Date().toInstant(),
						checksum,
						Files.size(f.toPath()),
						"STANDARD");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			})
			.collect(Collectors.toList());

		List<String> commonPrefixes = normalizeDelimiter(delimiter).map(del -> {
			return files.stream()
				.map(f -> commonPrefix(f.getKey(), prefixNoLeadingSlash, del).orElse(""))
				.distinct()
				.sorted()
				.collect(Collectors.toList());
		}).orElse(Collections.emptyList());

		List<Content> filteredFiles = files.stream().filter(f -> {
			return commonPrefixes.stream().noneMatch(p -> f.getKey().startsWith(p));
		}).sorted((f1, f2) -> f1.getKey().compareTo(f2.getKey())).collect(Collectors.toList());

		int count = maxkeys.orElse(MAX_KEYS);
		List<Content> content = filteredFiles.subList(0, Math.min(filteredFiles.size(), count));

		return new ListBucketResult(bucket, prefix.orElse(""), delimiter.orElse(""),
			count, filteredFiles.size() > count, content);
	}

	private Optional<String> commonPrefix(String dir, String p, String d) {
		int pos = dir.indexOf(d, p.length());
		if (pos == -1) {
			return Optional.empty();
		}
		return Optional.of(p + dir.substring(p.length(), pos) + d);
	}

	private Optional<String> normalizeDelimiter(Optional<String> delimiter) {
		return delimiter.filter(s -> !s.isEmpty());
	}

	private String fromOs(String path) {
		return path.replace(File.separatorChar, '/');
	}

	public CopyObjectResult copy(String sourceBucket, String sourceKey, String destinationBucket, String destinationKey)
		throws NoSuchBucketException, IOException {
		File sourceBucketFile = new File(dataDir + File.separator + sourceBucket);
		File destBucketFile = new File(dataDir + File.separator + destinationBucket);

		if (!sourceBucketFile.exists()) {
			throw new NoSuchBucketException("No such bucket: " + sourceBucket);
		}
		if (!destBucketFile.exists()) {
			throw new NoSuchBucketException("No such bucket: " + destinationBucket);
		}

		File sourceFile = new File(dataDir + File.separator + sourceBucket + File.separator + sourceKey);
		File destFile = new File(dataDir + File.separator + destinationBucket + File.separator + destinationKey);

		Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
		String checksum = DigestUtils.md5Hex(new FileInputStream(destFile));

		return new CopyObjectResult(checksum, Instant.now(), "", "", "", "");
	}

	public byte[] getObject(String bucket, String key) throws Exception {
		File sourceBucketFile = new File(dataDir + File.separator + bucket);
		File sourceFile = new File(dataDir + File.separator + bucket + File.separator + key);

		if (!sourceBucketFile.exists()) {
			throw new NoSuchBucketException("No such bucket: " + sourceBucketFile);
		}
		if (!sourceFile.exists()) {
			throw new NoSuchKeyException("No such key: " + sourceFile);
		}
		if (sourceFile.isDirectory()) {
			throw new NoSuchKeyException("No such key: " + sourceFile);
		}

		return Files.readAllBytes(sourceFile.toPath());
	}

	public boolean delete(String bucket, String key) throws NoSuchKeyException {
		File file = new File(dataDir + File.separator + bucket + File.separator + key);

		if (!file.exists()) {
			throw new NoSuchKeyException("No such key: " + file);
		}
		if (!file.isDirectory()) {
			return file.delete();
		}

		return false;
	}
}
