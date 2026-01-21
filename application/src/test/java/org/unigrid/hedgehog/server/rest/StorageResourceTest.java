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

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.bitcoinj.core.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import org.unigrid.hedgehog.model.storage.StorageLayout;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprint;
import org.unigrid.hedgehog.model.storage.crypto.FingerprintKeys;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.service.storage.FragmentTransport;
import org.unigrid.hedgehog.service.storage.StorageArbitraries;
import org.unigrid.hedgehog.service.storage.StorageFleet;
import org.unigrid.hedgehog.service.storage.StorageService;
import org.unigrid.hedgehog.service.storage.StorageTestData;

public class StorageResourceTest extends BaseRestClientTest {
	private static final String URL = "/storage";
	private static final List<String> COLLABORATORS = List.of("directory", "transport", "spork");

	/* Jersey buffers this much of a response before it commits the status and starts streaming chunks */
	private static final int COMMIT_BUFFER = 8192;
	private static final Duration STALL_LIMIT = Duration.ofSeconds(15);

	private final Map<String, Object> originals = new HashMap<>();
	private Client raw;

	@Inject
	private StorageService produced;

	@Inject
	private SporkDatabase sporkDatabase;

	@RequiredArgsConstructor
	private enum Failure {
		MALFORMED(Status.BAD_REQUEST, Status.BAD_REQUEST),
		DISABLED(Status.SERVICE_UNAVAILABLE, Status.SERVICE_UNAVAILABLE),
		NOT_FOUND(Status.NOT_FOUND, Status.NOT_FOUND),
		FIRST_STRIPE_LOST(Status.GONE, Status.NO_CONTENT),
		UNEXPECTED_ARGUMENT(Status.INTERNAL_SERVER_ERROR, Status.INTERNAL_SERVER_ERROR),
		UNEXPECTED_STATE(Status.INTERNAL_SERVER_ERROR, Status.INTERNAL_SERVER_ERROR);

		private final Status onRead;
		private final Status onDelete;
	}

	/* Answers every request by throwing, the way a broken transport would */
	@RequiredArgsConstructor
	private static final class FailingTransport implements FragmentTransport {
		private final Supplier<RuntimeException> failure;

		@Override
		public CompletableFuture<StorageAck> store(final Gridnode target, final byte[] fragment) {
			throw failure.get();
		}

		@Override
		public CompletableFuture<FragmentReply> fetch(final Gridnode target, final GroupId groupId) {
			throw failure.get();
		}

		@Override
		public CompletableFuture<FragmentStatus> has(final Gridnode target, final List<GroupId> groupIds) {
			throw failure.get();
		}

		@Override
		public CompletableFuture<StorageAck> delete(final Gridnode target, final GroupId groupId,
			final byte[] publicKey, final long timestamp, final byte[] signature) {

			throw failure.get();
		}
	}

	private static MultivaluedHashMap<String, Object> fingerprint(final String value) {
		return new MultivaluedHashMap<>(Map.of(StorageResource.FINGERPRINT_HEADER, value));
	}

	/* Well-formed fingerprints of files that were never stored */
	@Provide
	public Arbitrary<String> unknownFingerprints() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Fingerprint.SECRET_SIZE)
			.map(secret -> Base58.encodeChecked(StorageFormat.current().getId() & 0xFF, secret));
	}

	@Provide
	public Arbitrary<Tuple2<StorageSpork.SporkData, byte[]>> files() {
		return StorageArbitraries.parameters().flatMap(parameters -> StorageArbitraries.files(parameters)
			.map(file -> Tuple.of(parameters, file)));
	}

	@Provide
	public Arbitrary<Tuple2<StorageSpork.SporkData, byte[]>> nonEmptyFiles() {
		return files().filter(scenario -> scenario.get2().length > 0);
	}

	@BeforeTry
	@SneakyThrows
	public void prepareTry() {
		final SSLContext context = SSLContext.getInstance("TLS");

		for (final String name : COLLABORATORS) {
			originals.put(name, collaborator(name).get(produced));
		}

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
		raw = ClientBuilder.newBuilder().hostnameVerifier((hostname, session) -> true).sslContext(context).build();
	}

	@AfterTry
	@SneakyThrows
	public void finishTry() {
		raw.close();

		for (final String name : COLLABORATORS) {
			collaborator(name).set(produced, originals.get(name));
		}
	}

	private static Field collaborator(final String name) throws NoSuchFieldException {
		final Field field = StorageService.class.getDeclaredField(name);

		field.setAccessible(true);
		return field;
	}

	/* The produced service keeps its own code but talks to an in-memory fleet, as no test can reach the gridnodes
	   behind its real transport */
	@SneakyThrows
	private void wire(final GridnodeDirectory directory, final FragmentTransport transport,
		final Supplier<Optional<StorageSpork.SporkData>> spork) {

		collaborator("directory").set(produced, directory);
		collaborator("transport").set(produced, transport);
		collaborator("spork").set(produced, spork);
	}

	private StorageFleet wireFleet(final StorageFleet fleet) {
		wire(fleet.directory("client"), fleet.getTransport(), fleet::spork);
		return fleet;
	}

	private StorageFleet wireFleet(final StorageSpork.SporkData parameters) {
		return wireFleet(new StorageFleet(parameters, parameters.window()));
	}

	@SneakyThrows
	private static Fingerprint storeIn(final StorageFleet fleet, final byte[] file) {
		return fleet.service(new SecureRandom()).store(new ByteArrayInputStream(file));
	}

	private static void loseStripe(final StorageFleet fleet, final Fingerprint fingerprint, final int length,
		final int stripe) {

		final StorageLayout layout = StorageLayout.of(fleet.getParameters().layout(), length);
		final FingerprintKeys keys = new FingerprintKeys(fingerprint);

		for (int index = 0; index < layout.dataChunksIn(stripe) + layout.parityChunksIn(stripe); index++) {
			fleet.forget(new GroupKey(keys.chunkSeed(stripe, index)).groupId());
		}
	}

	/* Answers without the client's refusal of odd statuses, so that error bodies and headers can be inspected */
	private WebTarget rawTarget() {
		return raw.target(String.format("https://%s:%d%s", server.getRest().getHostName(), server.getRest().getPort(),
			URL));
	}

	/* Reads until the stream ends or breaks, counting what arrived either way */
	private static long drain(final Response response) {
		final byte[] buffer = new byte[COMMIT_BUFFER];
		long received = 0;

		try (InputStream body = response.readEntity(InputStream.class)) {
			for (int read = body.read(buffer); read >= 0; read = body.read(buffer)) {
				received += read;
			}
		} catch (IOException | RuntimeException ex) {
			/* A broken stream is one of the ways a short body may end */
		}

		return received;
	}

	@SneakyThrows
	@Property(tries = 10)
	public void storesReadsAndDeletesFiles(@ForAll("files") final Tuple2<StorageSpork.SporkData, byte[]> scenario) {
		wireFleet(scenario.get1());

		final Response stored = client.post(URL, Entity.entity(scenario.get2(), MediaType.APPLICATION_OCTET_STREAM));
		final String encoded = stored.readEntity(new GenericType<Map<String, String>>() { }).get("fingerprint");

		assertThat(Status.fromStatusCode(stored.getStatus()), equalTo(Status.CREATED));

		final Response read = client.getWithHeaders(URL, fingerprint(encoded));

		assertThat(Status.fromStatusCode(read.getStatus()), equalTo(Status.OK));
		assertThat(read.getHeaderString(StorageResource.FILE_SIZE_HEADER),
			equalTo(String.valueOf(scenario.get2().length)));
		assertThat(read.readEntity(byte[].class), equalTo(scenario.get2()));

		assertThat(Status.fromStatusCode(client.deleteWithHeaders(URL, fingerprint(encoded)).getStatus()),
			equalTo(Status.NO_CONTENT));
		assertThat(Status.fromStatusCode(client.getWithHeaders(URL, fingerprint(encoded)).getStatus()),
			equalTo(Status.NOT_FOUND));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void endsTheStreamEarlyWhenALaterStripeIsLost(@ForAll final long seed) {
		final StorageSpork.SporkData parameters = StorageTestData.parameters();
		final int stripe = parameters.layout().payloadSize() * parameters.getMaxOuterDataChunks();
		final Random random = new Random(seed);
		final byte[] file = new byte[(COMMIT_BUFFER / stripe + 1) * stripe + 1 + random.nextInt(2 * stripe)];
		final StorageFleet fleet = wireFleet(parameters);

		random.nextBytes(file);

		final Fingerprint stored = storeIn(fleet, file);

		loseStripe(fleet, stored, file.length, StorageLayout.of(parameters.layout(), file.length).stripes() - 1);

		final Response response = client.getWithHeaders(URL, fingerprint(stored.encode()));
		final long received = CompletableFuture.supplyAsync(() -> drain(response))
			.get(STALL_LIMIT.toMillis(), TimeUnit.MILLISECONDS);

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(Status.OK));
		assertThat(received, lessThan(Long.parseLong(response.getHeaderString(StorageResource.FILE_SIZE_HEADER))));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void reportsAFileLostFromItsStartAsGone(
		@ForAll("nonEmptyFiles") final Tuple2<StorageSpork.SporkData, byte[]> scenario) {

		final StorageFleet fleet = wireFleet(scenario.get1());
		final Fingerprint stored = storeIn(fleet, scenario.get2());

		loseStripe(fleet, stored, scenario.get2().length, 0);

		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.getWithHeaders(URL, fingerprint(stored.encode()))).getMessage(), startsWith("410 "));
		assertThat(Status.fromStatusCode(client.deleteWithHeaders(URL, fingerprint(stored.encode())).getStatus()),
			equalTo(Status.NO_CONTENT));
	}

	@Property(tries = 20)
	public void rejectsMalformedFingerprints(@ForAll @AlphaChars @StringLength(min = 1, max = 60) final String garbage) {
		sporkDatabase.setStorageSpork(new StorageSpork());

		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.getWithHeaders(URL, fingerprint(garbage))).getMessage(), startsWith("400 "));
		assertThat(assertThrows(ResponseOddityException.class,
			() -> client.deleteWithHeaders(URL, fingerprint(garbage))).getMessage(), startsWith("400 "));
	}

	@Example
	public void rejectsAMissingFingerprint() {
		assertThat(Status.fromStatusCode(rawTarget().request().get().getStatus()), equalTo(Status.BAD_REQUEST));
		assertThat(Status.fromStatusCode(rawTarget().request().delete().getStatus()), equalTo(Status.BAD_REQUEST));
	}

	/* Brings the fleet into the state the failure describes and returns the fingerprint header to send */
	private String arrange(final Failure failure, final StorageFleet fleet, final Fingerprint stored,
		final int length) {

		final String encoded = stored.encode();
		final Function<Function<String, RuntimeException>, FragmentTransport> failing = cause
			-> new FailingTransport(() -> cause.apply(encoded));

		switch (failure) {
			case MALFORMED -> {
				return encoded.substring(1);
			}
			case DISABLED -> wire(fleet.directory("client"), fleet.getTransport(), Optional::empty);
			case NOT_FOUND -> fleet.groups().forEach(fleet::forget);
			case FIRST_STRIPE_LOST -> loseStripe(fleet, stored, length, 0);
			case UNEXPECTED_ARGUMENT -> wire(fleet.directory("client"), failing.apply(IllegalArgumentException::new),
				fleet::spork);
			case UNEXPECTED_STATE -> wire(fleet.directory("client"), failing.apply(IllegalStateException::new),
				fleet::spork);
			default -> throw new IllegalArgumentException("Unknown failure");
		}

		return encoded;
	}

	@SneakyThrows
	@Property(tries = 20)
	public void neverEchoesTheFingerprintInAFailure(@ForAll final Failure failure, @ForAll final boolean read,
		@ForAll @Size(min = 1, max = 512) final byte[] file) {

		final StorageFleet fleet = wireFleet(StorageTestData.parameters());
		final Fingerprint stored = storeIn(fleet, file);
		final String sent = arrange(failure, fleet, stored, file.length);
		final Response response = rawTarget().request().header(StorageResource.FINGERPRINT_HEADER, sent)
			.method(read ? HttpMethod.GET : HttpMethod.DELETE);

		assertThat(Status.fromStatusCode(response.getStatus()), equalTo(read ? failure.onRead : failure.onDelete));
		assertThat(response.readEntity(String.class), not(containsString(stored.encode())));
		response.getStringHeaders().values().forEach(values -> values.forEach(value -> {
			assertThat(value, not(containsString(stored.encode())));
		}));
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

	@Property(tries = 5)
	public void isUnavailableWithTooFewGridnodes(@ForAll @Size(max = 64) final byte[] file,
		@ForAll @IntRange(max = 4) final int gridnodes) {

		wireFleet(new StorageFleet(StorageTestData.parameters(), gridnodes));

		assertThat(assertThrows(ResponseOddityException.class, () -> client.post(URL,
			Entity.entity(file, MediaType.APPLICATION_OCTET_STREAM))).getMessage(), startsWith("503 "));
	}
}
