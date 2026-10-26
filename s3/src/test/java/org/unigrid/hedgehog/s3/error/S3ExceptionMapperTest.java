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

package org.unigrid.hedgehog.s3.error;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Chars;
import net.jqwik.api.constraints.StringLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.index.BucketNotEmptyException;
import org.unigrid.hedgehog.s3.index.ConditionFailedException;
import org.unigrid.hedgehog.s3.index.NoSuchBucketException;
import org.unigrid.hedgehog.s3.index.NoSuchUploadException;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobUnavailableException;

public class S3ExceptionMapperTest {
	@Property
	public void findsTheS3ErrorBehindAnyWrapper(@ForAll S3ErrorCode code, @ForAll @StringLength(max = 3) String nesting) {
		final S3Exception error = new S3Exception(code);
		Throwable thrown = error;

		for (char wrapper : nesting.toCharArray()) {
			thrown = wrapper % 2 == 0 ? new IOException(thrown) : new BadRequestException(thrown);
		}

		assertThat(S3ExceptionMapper.translate(thrown), sameInstance(error));
	}

	@Example
	public void asksToRetryWhileTheStoreIsUnavailable() {
		assertThat(S3ExceptionMapper.translate(new IOException(new BlobUnavailableException("Too few gridnodes")))
			.getCode(), equalTo(S3ErrorCode.ServiceUnavailable)
		);
	}

	@Example
	public void namesTheS3ErrorsOfRefusedRoutes() {
		assertThat(S3ExceptionMapper.translate(new NotAllowedException("GET")).getCode(),
			equalTo(S3ErrorCode.MethodNotAllowed)
		);
		assertThat(S3ExceptionMapper.translate(new NotFoundException()).getCode(), equalTo(S3ErrorCode.NotImplemented));
		assertThat(S3ExceptionMapper.translate(new BadRequestException()).getCode(),
			equalTo(S3ErrorCode.InvalidRequest)
		);
	}

	@Example
	public void namesTheS3ErrorsOfRefusedIndexChangesAndDocuments() {
		assertThat(S3ExceptionMapper.translate(new NoSuchBucketException("photos")).getCode(),
			equalTo(S3ErrorCode.NoSuchBucket)
		);
		assertThat(S3ExceptionMapper.translate(new BucketNotEmptyException("photos")).getCode(),
			equalTo(S3ErrorCode.BucketNotEmpty)
		);
		assertThat(S3ExceptionMapper.translate(new NoSuchUploadException("upload")).getCode(),
			equalTo(S3ErrorCode.NoSuchUpload)
		);
		assertThat(S3ExceptionMapper.translate(new ConditionFailedException("guard")).getCode(),
			equalTo(S3ErrorCode.PreconditionFailed)
		);
		assertThat(S3ExceptionMapper.translate(new MalformedXmlException("body")).getCode(),
			equalTo(S3ErrorCode.MalformedXML)
		);
	}

	@Property(tries = 50)
	public void hidesTheDetailsOfUnexpectedFailures(@ForAll @AlphaChars @StringLength(min = 1) String secret) {
		for (Throwable thrown : new Throwable[] { new IllegalStateException(secret), new UncheckedIOException(
			new IOException(secret)), new BlobNotFoundException(secret), new InternalServerErrorException(secret) }) {

			final S3Exception error = S3ExceptionMapper.translate(thrown);

			assertThat(error.getCode(), equalTo(S3ErrorCode.InternalError));
			assertThat(error.getMessage(), not(equalTo(secret)));
		}
	}

	@Example
	public void writesTheErrorDocumentWithoutANamespace() throws Exception {
		final StringWriter xml = new StringWriter();
		final Marshaller marshaller = JAXBContext.newInstance(S3Error.class).createMarshaller();

		marshaller.setProperty(Marshaller.JAXB_FRAGMENT, true);
		marshaller.marshal(new S3Error("NoSuchKey", "The specified key does not exist.", "/bucket/a&b", "4442587F"),
			xml
		);

		assertThat(xml.toString(), equalTo("<Error><Code>NoSuchKey</Code><Message>The specified key does not "
			+ "exist.</Message><Resource>/bucket/a&amp;b</Resource><RequestId>4442587F</RequestId></Error>")
		);
	}

	@Property
	public void readsBackTheErrorItWrote(@ForAll S3ErrorCode code,
		@ForAll @Chars({ '<', '>', '&', '"', '\'', ' ', 'a', 'Z', '/' }) String resource) throws Exception {

		final JAXBContext context = JAXBContext.newInstance(S3Error.class);
		final S3Error error = new S3Error(code.name(), code.getMessage(), resource, "ID");
		final StringWriter xml = new StringWriter();

		context.createMarshaller().marshal(error, xml);
		assertThat(context.createUnmarshaller().unmarshal(new StringReader(xml.toString())), equalTo(error));
	}
}
