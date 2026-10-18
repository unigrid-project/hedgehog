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

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseDecoder;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.s3.auth.SdkSigner;
import org.unigrid.hedgehog.s3.auth.SigV4;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;

/*
   HTTP over a plain socket, for what the SDK and HttpURLConnection never do: pipeline, abort and overrun requests.
   Uploads take this way too, as the HTTP client of the SDK logs every byte it sends at the debug level the tests
   run with, which makes it slower than the paced resource it should outrun.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class RawHttp {
	private static final int MAX_BODY = 1024 * 1024;

	public record Answer(boolean complete, int status, HttpHeaders headers, int length) { }

	/* The head of a request signed like the SDK signs it, with a body that is not signed */
	public static byte[] signed(final int port, final String method, final String path, final long length) {
		final URI uri = S3Fixture.endpoint(port).resolve(path);
		final SdkHttpRequest request = AwsV4HttpSigner.create().sign(signing -> signing
			.identity(AwsCredentialsIdentity.create(S3Fixture.ACCESS_KEY, S3Fixture.SECRET_KEY))
			.request(SdkHttpRequest.builder().uri(uri).method(SdkHttpMethod.fromValue(method))
				.putHeader("Content-Length", Long.toString(length)).build())
			.putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, SigV4.SERVICE)
			.putProperty(AwsV4HttpSigner.REGION_NAME, SdkSigner.REGION)
			.putProperty(AwsV4HttpSigner.PAYLOAD_SIGNING_ENABLED, false)
		).request();
		final StringBuilder head = new StringBuilder(method + " " + uri.getRawPath() + " HTTP/1.1\r\n");

		request.headers().forEach((name, values) -> head.append(name).append(": ").append(values.getFirst())
			.append("\r\n")
		);

		return head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
	}

	/* The head of a request that authentication refuses before anything of its body is read */
	public static byte[] unsigned(final String method, final String path, final long length) {
		return (method + " " + path + " HTTP/1.1\r\nHost: " + S3Fixture.HOST + "\r\nContent-Length: " + length
			+ "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
	}

	public static void send(final OutputStream body, final long length, final AtomicLong sent) throws IOException {
		final byte[] block = new byte[PacedResource.BLOCK];

		while (sent.get() < length) {
			final int size = (int) Math.min(block.length, length - sent.get());

			body.write(block, 0, size);
			sent.addAndGet(size);
		}
	}

	/* Whatever does not parse as complete responses, one after another, never comes out of the decoder */
	public static List<Answer> answers(final InputStream input, final int count) throws IOException {
		final EmbeddedChannel decoder = new EmbeddedChannel(new HttpResponseDecoder(),
			new HttpObjectAggregator(MAX_BODY)
		);
		final List<Answer> answers = new ArrayList<>();
		final byte[] block = new byte[PacedResource.BLOCK];

		try {
			while (answers.size() < count) {
				final int read = input.read(block);

				if (read < 0) {
					throw new EOFException("Only " + answers.size() + " of " + count + " responses arrived");
				}

				decoder.writeInbound(Unpooled.copiedBuffer(block, 0, read));

				for (FullHttpResponse response = decoder.readInbound(); response != null;
					response = decoder.readInbound()) {

					answers.add(new Answer(response.decoderResult().isSuccess(), response.status().code(),
						response.headers(), response.content().readableBytes()
					));
					response.release();
				}
			}
		} finally {
			decoder.close();
			decoder.releaseInbound();
		}

		return answers;
	}
}
