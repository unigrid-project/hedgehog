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

package org.unigrid.hedgehog.server.rest;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.SequenceInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.bitcoinj.core.Base58;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.command.cli.StoragePut;
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
import picocli.CommandLine;

public class StorageResourceTest extends BaseRestClientTest {
	private static final String URL = "/storage";
	private static final List<String> COLLABORATORS = List.of("directory", "transport", "spork");

	/* Jersey buffers this much of a response before it commits the status and starts streaming chunks */
	private static final int COMMIT_BUFFER = 8192;
	private static final Duration STALL_LIMIT = Duration.ofSeconds(15);
	private static final Duration READ_LIMIT = Duration.ofMillis(250);
	private static final Duration SLOW_REPLY = READ_LIMIT.multipliedBy(2);

	/* Far more than any buffer between the client and the daemon holds back, so the daemon receives whole stripes */
	private static final int STREAMED_AHEAD = 1 << 16;

	private final Map<String, Object> originals = new HashMap<>();
	private Client raw;

	@Inject
	private StorageService produced;

	@Inject
	private SporkDatabase sporkDatabase;

	private enum Failure {
		MALFORMED(Status.BAD_REQUEST, Status.BAD_REQUEST),
		DISABLED(Status.SERVICE_UNAVAILABLE, Status.SERVICE_UNAVAILABLE),
		NOT_FOUND(Status.NOT_FOUND, Status.NOT_FOUND),
		FIRST_STRIPE_LOST(Status.GONE, Status.NO_CONTENT),
		UNEXPECTED_ARGUMENT(Status.INTERNAL_SERVER_ERROR, Status.INTERNAL_SERVER_ERROR, IllegalArgumentException::new),
		UNEXPECTED_STATE(Status.INTERNAL_SERVER_ERROR, Status.INTERNAL_SERVER_ERROR, IllegalStateException::new);

		private final Status onRead;
		private final Status onDelete;
		private final Optional<Function<String, RuntimeException>> thrown;

		Failure(final Status onRead, final Status onDelete) {
			this(onRead, onDelete, null);
		}

		Failure(final Status onRead, final Status onDelete, final Function<String, RuntimeException> thrown) {
			this.onRead = onRead;
			this.onDelete = onDelete;
			this.thrown = Optional.ofNullable(thrown);
		}
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

	/* Holds every reply back longer than a read may wait, the way a slow network would */
	@RequiredArgsConstructor
	private static final class SlowTransport implements FragmentTransport {
		private final FragmentTransport inner;
		private final Executor later = CompletableFuture.delayedExecutor(SLOW_REPLY.toMillis(), TimeUnit.MILLISECONDS);

		@Override
		public CompletableFuture<StorageAck> store(final Gridnode target, final byte[] fragment) {
			return inner.store(target, fragment).thenApplyAsync(Function.identity(), later);
		}

		@Override
		public CompletableFuture<FragmentReply> fetch(final Gridnode target, final GroupId groupId) {
			return inner.fetch(target, groupId).thenApplyAsync(Function.identity(), later);
		}

		@Override
		public CompletableFuture<FragmentStatus> has(final Gridnode target, final List<GroupId> groupIds) {
			return inner.has(target, groupIds).thenApplyAsync(Function.identity(), later);
		}

		@Override
		public CompletableFuture<StorageAck> delete(final Gridnode target, final GroupId groupId,
			final byte[] publicKey, final long timestamp, final byte[] signature) {

			return inner.delete(target, groupId, publicKey, timestamp, signature)
				.thenApplyAsync(Function.identity(), later);
		}
	}

	/* Keeps the rest of an upload back until a condition holds or the wait runs out, recording which of the two */
	@RequiredArgsConstructor
	private static final class HeldBack extends InputStream {
		private final BooleanSupplier release;
		private final AtomicBoolean released;
		private final InputStream rest;
		private boolean waited;

		@Override
		public int read() throws IOException {
			awaitRelease();
			return rest.read();
		}

		@Override
		public int read(final byte[] buffer, final int offset, final int length) throws IOException {
			awaitRelease();
			return rest.read(buffer, offset, length);
		}

		@SneakyThrows
		private void awaitRelease() {
			final long deadline = System.nanoTime() + STALL_LIMIT.toNanos();

			while (!waited && !release.getAsBoolean() && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}

			released.compareAndSet(false, !waited && release.getAsBoolean());
			waited = true;
		}
	}

	/* The options a command connects by are faked afresh for every property while the server keeps the port it
	   started on, so the command is handed the client of the running server instead */
	private static final class ConnectedStoragePut extends StoragePut {
		void runWith(final RestClient rest) throws ResponseOddityException {
			execute(post(rest));
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
		raw = ClientBuilder.newBuilder().hostnameVerifier((hostname, session) -> true).sslContext(context)
			.register((ClientRequestFilter) request -> request.getHeaders().putSingle(HttpHeaders.AUTHORIZATION,
				bearer()))
			.build();
	}

	private String bearer() {
		return BearerTokenFilter.SCHEME + " " + server.getRest().getToken();
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

	/* The store answers only once the file is placed, so it may take longer than any read is allowed to */
	@SneakyThrows
	@Property(tries = 3)
	public void waitsForAStoreLongerThanForARead(@ForAll @Size(min = 1, max = 512) final byte[] file) {
		final StorageFleet fleet = new StorageFleet(StorageTestData.parameters(), StorageTestData.parameters().window());

		wire(fleet.directory("client"), new SlowTransport(fleet.getTransport()), fleet::spork);

		try (RestClient impatient = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true,
			server.getRest().getToken(), READ_LIMIT)) {

			final long started = System.nanoTime();
			final Response stored = impatient.post(URL, Entity.entity(file, MediaType.APPLICATION_OCTET_STREAM));
			final Duration took = Duration.ofNanos(System.nanoTime() - started);
			final String encoded = stored.readEntity(new GenericType<Map<String, String>>() { }).get("fingerprint");

			assertThat(Status.fromStatusCode(stored.getStatus()), equalTo(Status.CREATED));
			assertThat(took, greaterThan(READ_LIMIT));
			assertThat(assertThrows(ProcessingException.class, () -> impatient.getWithHeaders(URL,
				fingerprint(encoded))).getCause(), instanceOf(SocketTimeoutException.class));
		}
	}

	/* A connection that buffers a body whole sends none of it before the file ends, so a daemon that places groups
	   while the rest of the file is still held back shows that the upload streams */
	@SneakyThrows
	@Property(tries = 3)
	public void streamsAnUploadAsItIsRead(@ForAll final long seed) {
		final StorageFleet fleet = wireFleet(StorageTestData.parameters());
		final Random random = new Random(seed);
		final byte[] file = new byte[STREAMED_AHEAD + 1 + random.nextInt(STREAMED_AHEAD)];
		final AtomicBoolean placedEarly = new AtomicBoolean();

		random.nextBytes(file);

		final InputStream upload = new SequenceInputStream(new ByteArrayInputStream(file, 0, STREAMED_AHEAD),
			new HeldBack(() -> !fleet.groups().isEmpty(), placedEarly,
				new ByteArrayInputStream(file, STREAMED_AHEAD, file.length - STREAMED_AHEAD)));
		final Response stored = client.postStream(URL, upload, file.length);
		final String encoded = stored.readEntity(new GenericType<Map<String, String>>() { }).get("fingerprint");

		assertThat(Status.fromStatusCode(stored.getStatus()), equalTo(Status.CREATED));
		assertThat(placedEarly.get(), is(true));
		assertThat(client.getWithHeaders(URL, fingerprint(encoded)).readEntity(byte[].class), equalTo(file));
	}

	@SneakyThrows
	@Property(tries = 3)
	public void storesAFileFromTheCommandLine(@ForAll("files") final Tuple2<StorageSpork.SporkData, byte[]> scenario) {
		final PrintStream console = System.out;
		final ByteArrayOutputStream printed = new ByteArrayOutputStream();

		wireFleet(scenario.get1());

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final ConnectedStoragePut command = new ConnectedStoragePut();

			Files.write(fs.getPath("/upload"), scenario.get2());
			new CommandLine(command).registerConverter(Path.class, fs::getPath).parseArgs("/upload");
			System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
			command.runWith(client);
		} finally {
			System.setOut(console);
		}

		final String encoded = printed.toString(StandardCharsets.UTF_8).trim();

		assertThat(client.getWithHeaders(URL, fingerprint(encoded)).readEntity(byte[].class), equalTo(scenario.get2()));
	}

	/* Sent over a bare socket, as no HTTP client lets a caller choose the framing of a request */
	@SneakyThrows
	private String rawStatus(final String framing, final String body) {
		final SSLContext context = SSLContext.getInstance("TLS");

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		try (Socket socket = context.getSocketFactory().createSocket(server.getRest().getHostName(),
			server.getRest().getPort())) {

			socket.setSoTimeout(Math.toIntExact(STALL_LIMIT.toMillis()));
			socket.getOutputStream().write(("POST " + URL + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: "
				+ MediaType.APPLICATION_OCTET_STREAM + "\r\nAuthorization: " + bearer() + "\r\n" + framing
				+ "\r\n\r\n" + body)
				.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().flush();
			return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
				.readLine();
		}
	}

	@Property(tries = 5)
	public void requiresTheLengthOfAnUpload(@ForAll @AlphaChars @StringLength(min = 1, max = 200) final String content) {
		final StorageFleet fleet = wireFleet(StorageTestData.parameters());
		final String chunks = Integer.toHexString(content.length()) + "\r\n" + content + "\r\n0\r\n\r\n";

		assertThat(rawStatus("Transfer-Encoding: chunked", chunks), startsWith("HTTP/1.1 411"));
		assertThat(fleet.groups().isEmpty(), is(true));
	}

	@Property(tries = 5)
	public void refusesUploadsOverTheCap(@ForAll @LongRange(min = StorageResource.MAX_UPLOAD_BYTES + 1) final long length) {
		final StorageFleet fleet = wireFleet(StorageTestData.parameters());

		assertThat(rawStatus("Content-Length: " + length, ""), startsWith("HTTP/1.1 413"));
		assertThat(fleet.groups().isEmpty(), is(true));
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

	/* Brings the fleet into the state the failure describes and returns the fingerprint header to send. Unexpected
	   failures put the fingerprint into the exception on purpose, to prove it never reaches the response. */
	private String arrange(final Failure failure, final StorageFleet fleet, final Fingerprint stored,
		final int length) {

		final String encoded = stored.encode();

		if (failure == Failure.MALFORMED) {
			return encoded.substring(1);
		}

		if (failure.thrown.isPresent()) {
			wire(fleet.directory("client"), new FailingTransport(() -> failure.thrown.get().apply(encoded)),
				fleet::spork);
		} else {
			damage(failure, fleet, stored, length);
		}

		return encoded;
	}

	private void damage(final Failure failure, final StorageFleet fleet, final Fingerprint stored, final int length) {
		switch (failure) {
			case DISABLED -> wire(fleet.directory("client"), fleet.getTransport(), Optional::empty);
			case NOT_FOUND -> fleet.groups().forEach(fleet::forget);
			case FIRST_STRIPE_LOST -> loseStripe(fleet, stored, length, 0);
			default -> throw new IllegalArgumentException("Nothing to damage for " + failure);
		}
	}

	private static void assertNothingEchoes(final Response response, final List<String> secrets) {
		final String body = response.readEntity(String.class);

		for (final String secret : secrets) {
			assertThat(body, not(containsString(secret)));
			response.getStringHeaders().values().forEach(values -> values.forEach(value -> {
				assertThat(value, not(containsString(secret)));
			}));
		}
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
		assertNothingEchoes(response, List.of(stored.encode(), sent));
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
