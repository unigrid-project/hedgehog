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

package org.unigrid.hedgehog.s3.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class FormDataTest {
	private static final String BOUNDARY = "----FormBoundary7MA4YWxk";

	/* The content is drawn from what a delimiter is made of, but never holds the X that ends every boundary, so it
	   comes as close to a delimiter as it can without being one */
	private static final String CONTENT = "\r\n-ab";

	record Field(String name, Optional<String> filename, Optional<String> type, String content) {
		private String encoded() {
			return "Content-Disposition: form-data; name=\"" + name + "\""
				+ filename.map(file -> "; filename=\"" + file + "\"").orElse("") + "\r\n"
				+ type.map(value -> "Content-Type: " + value + "\r\n").orElse("") + "\r\n" + content;
		}
	}

	private static String form(final String boundary, final String preamble, final List<Field> fields,
		final String epilogue) {

		final StringBuilder form = new StringBuilder(preamble);

		fields.forEach(field -> form.append("--").append(boundary).append("\r\n").append(field.encoded())
			.append("\r\n"));

		return form.append("--").append(boundary).append("--").append(epilogue).toString();
	}

	/* Hands out at most the given number of bytes per read, as a network does */
	private static InputStream trickle(final String body, final int chunk) {
		return new FilterInputStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
			@Override
			public int read(final byte[] buffer, final int offset, final int length) throws IOException {
				return super.read(buffer, offset, Math.min(length, chunk));
			}
		};
	}

	private static String read(final InputStream body, final int size) throws IOException {
		final ByteArrayOutputStream content = new ByteArrayOutputStream();
		final byte[] block = new byte[size];

		for (int read = body.read(block); read >= 0; read = body.read(block)) {
			content.write(block, 0, read);
		}

		return content.toString(StandardCharsets.UTF_8);
	}

	private static List<Field> parse(final FormData form, final int size) throws IOException {
		final List<Field> fields = new ArrayList<>();

		for (Optional<FormData.Part> part = form.next(); part.isPresent(); part = form.next()) {
			fields.add(new Field(part.get().name(), part.get().filename(), part.get().contentType(),
				read(part.get().body(), size)
			));
		}

		return fields;
	}

	private static S3ErrorCode refusal(final Expect.Action action) {
		return Expect.assertThrows(S3Exception.class, action).getCode();
	}

	@Provide
	Arbitrary<String> boundaries() {
		return Arbitraries.strings().withChars("ab-").ofMaxLength(10).map(boundary -> boundary + "X");
	}

	@Provide
	Arbitrary<List<Field>> fields() {
		final Arbitrary<Optional<String>> optional = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
			.optional();

		return Combinators.combine(Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8), optional, optional,
			Arbitraries.strings().withChars(CONTENT).ofMaxLength(300)).as(Field::new).list().ofMaxSize(5);
	}

	@Property
	public void readsBackEveryPartHoweverTheBodyArrives(@ForAll("boundaries") final String boundary,
		@ForAll("fields") final List<Field> fields, @ForAll @IntRange(min = 1, max = 40) final int chunk,
		@ForAll @IntRange(min = 1, max = 40) final int size) throws IOException {

		final String body = form(boundary, "preamble\r\n", fields, "\r\nepilogue");

		assertThat(parse(new FormData(trickle(body, chunk), boundary), size), equalTo(fields));
	}

	@Example
	public void skipsWhatIsLeftOfAPartOnTheWayToTheNext() throws IOException {
		final List<Field> fields = List.of(new Field("first", Optional.empty(), Optional.empty(),
			"x".repeat(200_000)), new Field("second", Optional.empty(), Optional.empty(), "kept")
		);
		final FormData form = new FormData(trickle(form(BOUNDARY, "", fields, ""), 1000), BOUNDARY);

		assertThat(form.next().orElseThrow().body().read(), equalTo((int) 'x'));
		assertThat(read(form.next().orElseThrow().body(), 10), equalTo("kept"));
		assertThat(form.next(), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheParametersOfAPartAsBrowsersWriteThem() throws IOException {
		final String body = "--" + BOUNDARY + "  \r\ncontent-disposition: Form-Data; NAME=\"file\"; "
			+ "filename=\"a;b c.jpg\"\r\nCONTENT-TYPE: image/jpeg\r\n\r\njpeg\r\n--" + BOUNDARY + "--";
		final FormData.Part part = new FormData(trickle(body, 7), BOUNDARY).next().orElseThrow();

		assertThat(part.name(), equalTo("file"));
		assertThat(part.filename(), equalTo(Optional.of("a;b c.jpg")));
		assertThat(part.contentType(), equalTo(Optional.of("image/jpeg")));
		assertThat(read(part.body(), 3), equalTo("jpeg"));
	}

	@Example
	public void refusesABodyCutShortInAPart() throws IOException {
		final FormData form = new FormData(trickle("--" + BOUNDARY + "\r\nContent-Disposition: form-data; "
			+ "name=\"file\"\r\n\r\njpeg\r\n--" + BOUNDARY.substring(1), 5), BOUNDARY
		);
		final InputStream body = form.next().orElseThrow().body();

		assertThat(refusal(() -> body.readAllBytes()), equalTo(S3ErrorCode.MalformedPOSTRequest));
	}

	@Example
	public void refusesWhatIsNoFormData() {
		final List<String> malformed = List.of("", "--" + BOUNDARY, "--" + BOUNDARY + "\r\n\r\nvalue",
			"--" + BOUNDARY + "\r\nContent-Disposition: attachment; name=\"key\"\r\n\r\n",
			"--" + BOUNDARY + "\r\nContent-Disposition: form-data\r\n\r\n",
			"--" + BOUNDARY + "\r\nContent-Disposition form-data; name=\"key\"\r\n\r\n",
			"--" + BOUNDARY + "junk\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\n",
			"--" + BOUNDARY + "\r\nX-Long: " + "x".repeat(20_000) + "\r\n\r\n"
		);

		for (String body : malformed) {
			assertThat(body, refusal(() -> new FormData(trickle(body, 100), BOUNDARY).next()),
				equalTo(S3ErrorCode.MalformedPOSTRequest)
			);
		}
	}

	@Example
	public void refusesABoundaryNoFormCouldHave() {
		for (String boundary : List.of("", "b".repeat(71))) {
			assertThat(refusal(() -> new FormData(trickle("", 1), boundary)),
				equalTo(S3ErrorCode.MalformedPOSTRequest)
			);
		}
	}
}
