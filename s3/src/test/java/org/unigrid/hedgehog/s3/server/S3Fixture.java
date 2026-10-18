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

package org.unigrid.hedgehog.s3.server;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.unigrid.hedgehog.s3.spi.CredentialStore;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class S3Fixture {
	public static final String HOST = "localhost";
	public static final String DOMAIN = "s3.test";
	public static final String ACCESS_KEY = "HKFIXTURE0ACCESS0KEY";
	public static final String SECRET_KEY = "fixture/secret+key0for0the0embedded0node";

	/* Keeps the client from reading the profile files of whoever runs the tests */
	private static final ProfileFile NO_PROFILES = ProfileFile.builder().content(new ByteArrayInputStream(new byte[0]))
		.type(ProfileFile.Type.CONFIGURATION).build();

	public static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	public static CredentialStore credentials() {
		return accessKeyId -> ACCESS_KEY.equals(accessKeyId) ? Optional.of(SECRET_KEY) : Optional.empty();
	}

	public static S3ServerConfig config(final int port) {
		return S3ServerConfig.builder().host(HOST).port(port).domain(DOMAIN).tls(false).build();
	}

	public static S3Server start(final int port, final Class<?>... resources) throws Exception {
		return S3Server.start(config(port), S3Server.resources(config(port), new MemoryBlobStore(), credentials())
			.registerClasses(resources)
		);
	}

	public static URI endpoint(final int port) {
		return URI.create("http://" + HOST + ":" + port + "/");
	}

	public static S3ClientBuilder client(final URI endpoint, final String secretKey) {
		return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).forcePathStyle(true)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, secretKey)))
			.overrideConfiguration(configuration -> configuration.defaultProfileFile(NO_PROFILES)
				.defaultProfileName("default"));
	}

	public static S3Client client(final int port) {
		return client(endpoint(port), SECRET_KEY).build();
	}

	/* Every subdomain of the test domain resolves to this machine, as a wildcard DNS record would */
	public static S3Client virtualHostClient(final int port) {
		return client(URI.create("http://" + DOMAIN + ":" + port), SECRET_KEY).forcePathStyle(false)
			.httpClientBuilder(Apache5HttpClient.builder().dnsResolver(new SystemDefaultDnsResolver() {
				@Override
				public InetAddress[] resolve(final String host) {
					return new InetAddress[] { InetAddress.getLoopbackAddress() };
				}
			})).build();
	}
}
