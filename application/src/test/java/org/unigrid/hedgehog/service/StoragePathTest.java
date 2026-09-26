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

package org.unigrid.hedgehog.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.service.StorageNames.DATA_DIR;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

public class StoragePathTest {
	private static void assertRejected(Supplier<Path> path) {
		try {
			assertThat("Accepted " + path.get(), false);
		} catch (InvalidPathException ex) {
			assertThat(ex.getReason(), is(not(equalTo(""))));
		}
	}

	private static boolean isConfinedOrRejected(Supplier<Path> path, Predicate<Path> confined) {
		try {
			return confined.test(path.get());
		} catch (InvalidPathException ex) {
			return true;
		}
	}

	@Provide
	public Arbitrary<String> provideEscaping() {
		return StorageNames.escaping();
	}

	@Provide
	public Arbitrary<String> provideAny() {
		return StorageNames.any();
	}

	@Provide
	public Arbitrary<String> providePlain() {
		return StorageNames.plain();
	}

	@Property
	public void shouldRejectEscapingBucket(@ForAll("provideEscaping") String bucket) {
		assertRejected(() -> StoragePath.bucket(DATA_DIR, bucket));
	}

	@Property
	public void shouldRejectEscapingKey(@ForAll("providePlain") String bucket, @ForAll("provideEscaping") String key) {
		assertRejected(() -> StoragePath.object(DATA_DIR, bucket, key));
	}

	@Property
	public void shouldRejectNestedBucket(@ForAll("providePlain") String parent, @ForAll("providePlain") String child) {
		assertRejected(() -> StoragePath.bucket(DATA_DIR, parent + "/" + child));
	}

	@Property
	public void shouldKeepPlainNames(@ForAll("providePlain") String bucket, @ForAll("providePlain") String key) {
		assertThat(StoragePath.bucket(DATA_DIR, bucket), equalTo(DATA_DIR.resolve(bucket)));
		assertThat(StoragePath.object(DATA_DIR, bucket, key), equalTo(DATA_DIR.resolve(bucket).resolve(key)));
	}

	@Property(tries = 2000)
	public void shouldConfineAnyBucket(@ForAll("provideAny") String bucket) {
		assertThat(isConfinedOrRejected(() -> StoragePath.bucket(DATA_DIR, bucket),
			path -> DATA_DIR.equals(path.getParent())), is(true)
		);
	}

	@Property(tries = 2000)
	public void shouldConfineAnyObject(@ForAll("providePlain") String bucket, @ForAll("provideAny") String key) {
		final Path bucketPath = DATA_DIR.resolve(bucket);

		assertThat(isConfinedOrRejected(() -> StoragePath.object(DATA_DIR, bucket, key),
			path -> path.startsWith(bucketPath) && !path.equals(bucketPath)), is(true)
		);
	}
}
