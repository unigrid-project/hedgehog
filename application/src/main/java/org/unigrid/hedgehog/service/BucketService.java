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
 package org.unigrid.hedgehog.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.Owner;

@ApplicationScoped
public class BucketService {

    @Inject
    private ApplicationDirectory applicationDirectory;

    private Path dataDir;

    @PostConstruct
    private void init() {
        dataDir = applicationDirectory.getUserDataDir().resolve("s3data");
    }

    public String create(String name) {

        final File customDir = dataDir.resolve(name).toFile();
        String location = "";

        try {
            if (!customDir.exists()) {
                customDir.mkdirs();
            }
            location = customDir.getName();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return location;
    }

    public ListAllMyBucketsResult listBuckets() {

        if (!dataDir.toFile().exists()) {
            dataDir.toFile().mkdirs();
        }

        File[] files = dataDir.toFile().listFiles();

        List<String> directories = files == null
                ? List.of()
                : Stream.of(files)
                        .filter(File::isDirectory)
                        .map(File::getName)
                        .collect(Collectors.toList());

        final ArrayList<Bucket> buckets = new ArrayList<>();

        for (String directory : directories) {
            Bucket bucket = new Bucket(Instant.now(), directory);
            buckets.add(bucket);
        }

        Owner owner = new Owner(
                "user",
                RandomStringUtils.randomNumeric(20)
        );

        ListAllMyBucketsResult result = new ListAllMyBucketsResult();
        result.setBuckets(buckets);
        result.setOwner(owner);

        return result;
    }

    public boolean delete(String bucketName) throws IOException {

        final File customDir = dataDir.resolve(bucketName).toFile();

        if (!customDir.exists()) {
            return false;
        }

        Files.walk(customDir.toPath())
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);

        return true;
    }
}