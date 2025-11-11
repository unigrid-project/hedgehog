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

package org.unigrid.hedgehog.server.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Map;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.internal.state.SavePoint;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.bitcoinj.core.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.objenesis.ObjenesisStd;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.service.storage.DataLossException;
import org.unigrid.hedgehog.service.storage.Retrieval;
import org.unigrid.hedgehog.service.storage.StorageService;

public class StorageResourceTest extends BaseRestClientTest {
	private static final String URL = "/storage";

	private SavePoint mocks;

	@Inject
	private SporkDatabase sporkDatabase;

	private static MultivaluedHashMap<String, Object> fingerprint(final String value) {
		return new MultivaluedHashMap<>(Map.of(StorageResource.FINGERPRINT_HEADER, value));
	}

	/* Well-formed fingerprints of files that were never stored */
	@Provide
	public Arbitrary<String> unknownFingerprints() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Fingerprint.SECRET_SIZE)
			.map(secret -> Base58.encodeChecked(StorageFormat.current().getId() & 0xFF, secret));
	}

	/* Fakes otherwise outlive their property and would stand in for the real service in the ones that follow */
	@BeforeTry
	public void saveMocks() {
		mocks = new SavePoint();
	}

	@AfterTry
	public void restoreMocks() {
		mocks.rollback();
	}

	private void serve(final byte[] file) {
		final Retrieval retrieval = new ObjenesisStd().newInstance(Retrieval.class);

		new MockUp<Retrieval>() {
			@Mock public long size() {
				return file.length;
			}

			@Mock public void writeTo(final OutputStream output) throws IOException {
				output.write(file);
			}
		};

		new MockUp<StorageService>() {
			@Mock public Retrieval open(final Fingerprint fingerprint) {
				return retrieval;
			}
		};
	}

	@SneakyThrows
	@Property(tries = 20)
	public void returnsTheFingerprintOfAStoredFile(@ForAll @Size(max = 4096) final byte[] file) {
		final Fingerprint expected = Fingerprint.generate(new SecureRandom());

		new MockUp<StorageService>() {
			@Mock public Fingerprint store(final InputStream input) throws IOException {
				assertThat(input.readAllBytes(), equalTo(file));
				return expected;
			}
		};

		final Response response = client.post(URL, Entity.entity(file, MediaType.APPLICATION_OCTET_STREAM));

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.CREATED));
		assertThat(response.readEntity(String.class), containsString(expected.encode()));
	}

	@SneakyThrows
	@Property(tries = 20)
	public void streamsAStoredFileBack(@ForAll @Size(max = 4096) final byte[] file,
		@ForAll("unknownFingerprints") final String encoded) {

		serve(file);

		final Response response = client.getWithHeaders(URL, fingerprint(encoded));

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.OK));
		assertThat(response.getLength(), equalTo(file.length));
		assertThat(response.readEntity(byte[].class), equalTo(file));
	}

	@Property(tries = 20)
	public void rejectsMalformedFingerprints(@ForAll @AlphaChars @StringLength(min = 1, max = 60) final String garbage) {
		sporkDatabase.setStorageSpork(new StorageSpork());

		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.getWithHeaders(URL, fingerprint(garbage))).getMessage(), startsWith("400 "));
		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.deleteWithHeaders(URL, fingerprint(garbage))).getMessage(), startsWith("400 "));
	}

	@SneakyThrows
	@Property(tries = 10)
	public void reportsUnknownFingerprintsAsMissing(@ForAll("unknownFingerprints") final String encoded) {
		sporkDatabase.setStorageSpork(new StorageSpork());

		assertThat(Status.fromStatusCode(client.getWithHeaders(URL, fingerprint(encoded)).getStatus()),
			equalTo(Status.NOT_FOUND));
		assertThat(Status.fromStatusCode(client.deleteWithHeaders(URL, fingerprint(encoded)).getStatus()),
			equalTo(Status.NOT_FOUND));
	}

	@Property(tries = 5)
	public void isUnavailableWithoutASpork(@ForAll("unknownFingerprints") final String encoded) {
		sporkDatabase.setStorageSpork(null);

		assertThat(assertThrows(ResponseOddityException.class, () -> client.post(URL,
			Entity.entity(new byte[1], MediaType.APPLICATION_OCTET_STREAM))).getMessage(), startsWith("503 "));
		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.getWithHeaders(URL, fingerprint(encoded))).getMessage(), startsWith("503 "));
		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.deleteWithHeaders(URL, fingerprint(encoded))).getMessage(), startsWith("503 "));
	}

	@Example
	public void reportsLostFilesAsGone() {
		new MockUp<StorageService>() {
			@Mock public Retrieval open(final Fingerprint fingerprint) throws DataLossException {
				throw new DataLossException(0);
			}
		};

		assertThat(assertThrows(ResponseOddityException.class, () -> client.getWithHeaders(URL,
			fingerprint(Fingerprint.generate(new SecureRandom()).encode()))).getMessage(), startsWith("410 "));
	}
}
