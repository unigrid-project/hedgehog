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
import static org.unigrid.hedgehog.service.StorageAssertions.assertRejected;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.codec.digest.DigestUtils;

public class ObjectServiceTest {
	private static final String BUCKET = "bucket";
	private static final String KEY = "key";

	private final ObjectService service = new ObjectService();

	@BeforeTry
	@SneakyThrows
	public void beforeTry() {
		service.setDataDir(StorageAssertions.inMemoryDataDir());
		Files.createDirectories(service.getDataDir().resolve(BUCKET));
	}

	@Provide
	public Arbitrary<String> providePlain() {
		return StorageNames.plain();
	}

	@Provide
	public Arbitrary<String> provideEscaping() {
		return StorageNames.escaping();
	}

	@Property
	public void shouldRejectEscapingKey(@ForAll("provideEscaping") String key) {
		assertRejected(() -> service.put(BUCKET, key, InputStream.nullInputStream()));
		assertRejected(() -> service.getObject(BUCKET, key));
		assertRejected(() -> service.delete(BUCKET, key));
		assertRejected(() -> service.copy(BUCKET, key, BUCKET, KEY));
		assertRejected(() -> service.copy(BUCKET, KEY, BUCKET, key));
	}

	@Property
	public void shouldRejectEscapingBucket(@ForAll("provideEscaping") String bucket) {
		assertRejected(() -> service.put(bucket, KEY, InputStream.nullInputStream()));
		assertRejected(() -> service.listBucket(bucket, Optional.empty(), Optional.empty(), Optional.empty()));
		assertRejected(() -> service.getObject(bucket, KEY));
		assertRejected(() -> service.delete(bucket, KEY));
		assertRejected(() -> service.copy(bucket, KEY, BUCKET, KEY));
		assertRejected(() -> service.copy(BUCKET, KEY, bucket, KEY));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldStoreReadAndCopyPlainKeys(@ForAll("providePlain") String key, @ForAll byte[] data) {
		service.put(BUCKET, key, new ByteArrayInputStream(data));
		assertThat(service.getObject(BUCKET, key), equalTo(data));

		assertThat(service.copy(BUCKET, key, BUCKET, key + "-copy").getETag(), equalTo(DigestUtils.md5Hex(data)));
		assertThat(service.getObject(BUCKET, key + "-copy"), equalTo(data));
	}
}
