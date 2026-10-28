/*
	Hedgehog
	Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

	This program is free software: you can redistribute it and/or modify it under the terms of the
	addended GNU Affero General Public License as published by the Free Software Foundation, version 3
	of the License (see COPYING and COPYING.addendum).

	This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
	the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General
	Public License for more details.

	You should have received an addended copy of the GNU Affero General Public License with this program.
	If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server.s3;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import lombok.SneakyThrows;
import me.alexpanov.net.FreePortFinder;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.PropagationMode;
import org.jboss.weld.environment.se.WeldContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.command.option.S3Options;
import org.unigrid.hedgehog.jqwik.NamedCDIProvider;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeFixtures;
import org.unigrid.hedgehog.model.gridnode.GridnodeIdentity;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.producer.GridnodeProducer;
import org.unigrid.hedgehog.model.producer.StorageProducer;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceConfig;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.p2p.TopologyThread;
import org.unigrid.hedgehog.service.storage.StorageBlobStore;
import org.unigrid.hedgehog.service.storage.StorageService;
import org.unigrid.hedgehog.service.storage.StorageTestData;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

@WeldSetup({ TestServer.class, TopologyThread.class })
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class S3NetworkTest extends BaseServerTest {
	private static final StorageSpork.SporkData PARAMETERS = parameters();
	private static final TestClock CLOCK = new TestClock();
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
	private static final String ROOT_FILE = "root";
	private static final int MIB = 1 << 20;

	/* Keeps the client from reading the profile files of whoever runs the tests */
	private static final ProfileFile NO_PROFILES = ProfileFile.builder()
		.content(new ByteArrayInputStream(new byte[0]))
		.type(ProfileFile.Type.CONFIGURATION).build();

	private record Member(String container, Gridnode gridnode, Node client) { }

	private record Mesh(List<Member> members, Member gateway) { }

	private static Mesh sharedMesh;
	private static Signature proposer;
	private static Signature cosigner;
	private static boolean gatewayInstantiated;

	/* The fragment counts of the storage tests, in chunks large enough that a five-mebibyte part stays quick */
	private static StorageSpork.SporkData parameters() {
		final StorageSpork.SporkData data = StorageTestData.parameters();

		data.setChunkSize(64 * 1024);
		data.setFragmentSize(8 * 1024);
		return data;
	}

	@Override
	protected boolean isolateContainers() {
		return true;
	}

	@SneakyThrows
	@BeforeProperty
	private void mockEnvironment() {
		new InMemoryDataDirectoryMockUp();

		if (proposer == null) {
			proposer = new Signature();
			cosigner = new Signature();
		}

		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}

			@Mock public /* static */ String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};

		new MockUp<GridnodeProducer>() {
			@Mock @SneakyThrows
			public GridnodeIdentity gridnodeIdentity() {
				return GridnodeIdentity.of(new Signature());
			}
		};

		new MockUp<StorageProducer>() {
			@Mock @SneakyThrows
			public FragmentStore fragmentStore() {
				return new FragmentStore(Jimfs.newFileSystem(Configuration.unix()).getPath("/fragments"), CLOCK);
			}
		};
	}

	private static <T> T bean(String container, Class<T> type) {
		return WeldContainer.instance(container).select(type).get();
	}

	@SneakyThrows
	private Mesh mesh() {
		if (sharedMesh == null) {
			sharedMesh = createMesh();
		}

		return sharedMesh;
	}

	/* Every container learns all signed gridnodes and dials every other server, as StorageNetworkTest does */
	@SneakyThrows
	private Mesh createMesh() {
		final List<Member> members = new ArrayList<>();

		for (int i = 0; i < servers().size(); i++) {
			final String container = containerName(i);
			final P2PServer p2p = servers().get(i).getP2p();
			final String address = p2p.getHostName() + ":" + p2p.getPort();
			final Gridnode gridnode = bean(container, GridnodeIdentity.class)
				.sign(Gridnode.Status.ACTIVE, address, GridnodeFixtures.sealingKey(),
					System.currentTimeMillis(), 0);
			final Node client = Node.fromAddress(address);

			client.setConnection(Optional.of(new P2PClient(client.getAddress().getHostString(),
				client.getAddress().getPort())));
			members.add(new Member(container, gridnode, client));
		}

		final StorageSpork spork = spork();

		members.forEach(member -> bean(member.container(), SporkDatabase.class).set(spork));
		members.forEach(member -> meshInto(member, members));

		return new Mesh(List.copyOf(members), members.get(0));
	}

	@SneakyThrows
	private StorageSpork spork() {
		final StorageSpork spork = new StorageSpork();

		spork.archive();
		spork.setData(PARAMETERS);
		spork.sign(proposer.getPrivateKey());
		spork.cosign(cosigner.getPrivateKey());
		assertThat(spork.isDoublySigned(), is(true));
		return spork;
	}

	private void meshInto(Member member, List<Member> members) {
		NamedCDIProvider.within(member.container(), () -> {
			final Topology topology = bean(member.container(), Topology.class);
			final TopologyThread.NodeConnectionHandler dial = bean(member.container(), TopologyThread.class)
				.new NodeConnectionHandler(topology);

			members.forEach(other -> topology.offerGridnode(other.gridnode()));
			members.stream().filter(other -> other != member).forEach(other -> {
				final Node peer = Node.builder().address(other.client().getAddress()).build();

				if (topology.addNode(peer)) {
					dial.accept(peer);
				}
			});
		});
	}

	private Session openSession(Mesh mesh) throws IOException {
		final int port = FreePortFinder.findFreeLocalPort();

		OptionsCli.parse(List.of("daemon"), "--s3host", "localhost", "--s3port", String.valueOf(port),
			"--s3plain", "--s3lifecycleinterval", "PT0S");

		final S3Credentials.Key key = new S3Credentials(S3Credentials.getFile()).create();

		return new Session(gateway(mesh.gateway()), client(port, key), S3Options.getIndexDirectory());
	}

	private static S3Gateway gateway(Member gateway) {
		final S3Gateway instance = bean(gateway.container(), S3Gateway.class);

		if (gatewayInstantiated) {
			instance.init();
		} else {
			CDIUtil.instantiate(instance);
			gatewayInstantiated = true;
		}

		return instance;
	}

	/* A part crosses the storage network before its request is answered, which outlasts a default socket timeout */
	private static S3Client client(int port, S3Credentials.Key key) {
		return S3Client.builder().endpointOverride(URI.create("http://localhost:" + port + "/"))
			.region(Region.US_EAST_1).forcePathStyle(true)
			.credentialsProvider(StaticCredentialsProvider.create(
				AwsBasicCredentials.create(key.accessKeyId(), key.secretKey())))
			.httpClientBuilder(Apache5HttpClient.builder().socketTimeout(REQUEST_TIMEOUT))
			.overrideConfiguration(configuration -> configuration.defaultProfileFile(NO_PROFILES)
				.defaultProfileName("default"))
			.build();
	}

	private static String bucket() {
		return "bucket-" + UUID.randomUUID();
	}

	private static int stripeBytes() {
		return PARAMETERS.layout().payloadSize() * PARAMETERS.layout().getMaxOuterDataChunks();
	}

	private static byte[] content(int size) {
		final byte[] content = new byte[size];

		new Random(size).nextBytes(content);
		return content;
	}

	private static byte[] concat(byte[] first, byte[] last) {
		final byte[] all = Arrays.copyOf(first, first.length + last.length);

		System.arraycopy(last, 0, all, first.length, last.length);
		return all;
	}

	private static byte[] read(S3Client client, String bucket, String key) throws IOException {
		try (ResponseInputStream<GetObjectResponse> object = client.getObject(
			request -> request.bucket(bucket).key(key))) {

			return object.readAllBytes();
		}
	}

	private static byte[] readRange(S3Client client, String bucket, String key, long first, long last)
		throws IOException {

		try (ResponseInputStream<GetObjectResponse> object = client.getObject(
			request -> request.bucket(bucket).key(key).range("bytes=" + first + "-" + last))) {

			return object.readAllBytes();
		}
	}

	@Example
	public void servesObjectsThroughTheNetwork() throws Exception {
		final Mesh mesh = mesh();

		try (Session session = openSession(mesh)) {
			final S3Client client = session.client();
			final String bucket = bucket();
			final byte[] small = content(64);
			final byte[] large = content(stripeBytes() * 2 + stripeBytes() / 2);

			client.createBucket(request -> request.bucket(bucket));
			client.putObject(request -> request.bucket(bucket).key("small"), RequestBody.fromBytes(small));
			client.putObject(request -> request.bucket(bucket).key("large"), RequestBody.fromBytes(large));

			assertThat(read(client, bucket, "small"), equalTo(small));
			assertThat(read(client, bucket, "large"), equalTo(large));
			assertThat(readRange(client, bucket, "large", 100, 199), equalTo(Arrays.copyOfRange(large, 100, 200)));
		}
	}

	@Example
	public void completesMultipartUploads() throws Exception {
		final Mesh mesh = mesh();

		try (Session session = openSession(mesh)) {
			final S3Client client = session.client();
			final String bucket = bucket();
			final String key = "multipart";
			final byte[] first = content(5 * MIB);
			final byte[] last = content(4321);

			client.createBucket(request -> request.bucket(bucket));

			final CreateMultipartUploadResponse upload = client.createMultipartUpload(
				request -> request.bucket(bucket).key(key));
			final UploadPartResponse partOne = client.uploadPart(
				request -> request.bucket(bucket).key(key).uploadId(upload.uploadId()).partNumber(1),
				RequestBody.fromBytes(first));
			final UploadPartResponse partTwo = client.uploadPart(
				request -> request.bucket(bucket).key(key).uploadId(upload.uploadId()).partNumber(2),
				RequestBody.fromBytes(last));

			client.completeMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(upload.uploadId())
				.multipartUpload(CompletedMultipartUpload.builder()
					.parts(List.of(CompletedPart.builder().partNumber(1).eTag(partOne.eTag()).build(),
						CompletedPart.builder().partNumber(2).eTag(partTwo.eTag()).build()))
					.build()));

			assertThat(read(client, bucket, key), equalTo(concat(first, last)));
		}
	}

	@Example
	public void spreadsFragmentsOverTheGridnodes() throws Exception {
		final Mesh mesh = mesh();

		try (Session session = openSession(mesh)) {
			final String bucket = bucket();

			session.client().createBucket(request -> request.bucket(bucket));
			session.client().putObject(request -> request.bucket(bucket).key("object"),
				RequestBody.fromBytes(content(stripeBytes() * 2)));

			final long holders = mesh.members().stream()
				.filter(member -> member != mesh.gateway())
				.filter(member -> !bean(member.container(), FragmentStore.class).groups().isEmpty())
				.count();

			assertThat(holders, greaterThanOrEqualTo((long) PARAMETERS.layout().guaranteedFragments() - 1));
		}
	}

	@Example
	public void recoversTheIndexFromStorage() throws Exception {
		final Mesh mesh = mesh();
		final Session session = openSession(mesh);

		try {
			final String bucket = bucket();
			final String key = "recovered";

			session.client().createBucket(request -> request.bucket(bucket));
			session.client().putObject(request -> request.bucket(bucket).key(key),
				RequestBody.fromBytes(content(2048)));

			session.close();

			final BlobId root = new BlobId(Files.readString(session.indexDirectory().resolve(ROOT_FILE)));
			final StorageService storage = bean(mesh.gateway().container(), StorageService.class);

			try (FileSystem files = Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("basic",
				"owner", "posix", "unix").build()); Namespace namespace = Namespace.mount(files.getPath("/recovered"),
				new StorageBlobStore(storage), root, NamespaceConfig.builder().build())) {

				assertThat(namespace.bucket(bucket).isPresent(), is(true));
				assertThat(namespace.latest(bucket, key).isPresent(), is(true));
			}
		} finally {
			session.close();
		}
	}

	@Example
	public void deletesObjects() throws Exception {
		final Mesh mesh = mesh();

		try (Session session = openSession(mesh)) {
			final S3Client client = session.client();
			final String bucket = bucket();

			client.createBucket(request -> request.bucket(bucket));
			client.putObject(request -> request.bucket(bucket).key("gone"), RequestBody.fromBytes(content(128)));
			client.deleteObject(request -> request.bucket(bucket).key("gone"));

			assertThrows(NoSuchKeyException.class,
				() -> client.getObject(request -> request.bucket(bucket).key("gone")));
		}
	}

	private static final class Session implements AutoCloseable {
		private final S3Gateway gateway;
		private final S3Client client;
		private final Path indexDirectory;
		private boolean closed;

		private Session(S3Gateway gateway, S3Client client, Path indexDirectory) {
			this.gateway = gateway;
			this.client = client;
			this.indexDirectory = indexDirectory;
		}

		private S3Client client() {
			return client;
		}

		private Path indexDirectory() {
			return indexDirectory;
		}

		@Override
		public void close() throws IOException {
			if (closed) {
				return;
			}

			closed = true;

			try {
				client.close();
			} finally {
				gateway.destroy();
			}
		}
	}
}
