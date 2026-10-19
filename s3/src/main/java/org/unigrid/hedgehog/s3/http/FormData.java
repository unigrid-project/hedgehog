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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   A multipart/form-data body, read one part at a time as it arrives. A part ends where CRLF, two dashes and the
   boundary follow it, so its body streams through a buffer that only ever holds back what could be the start of
   that delimiter. The headers of a part are bounded, and its body is bounded by whoever reads it.
*/
public final class FormData {
	private static final int BUFFER = 64 * 1024;
	private static final int MAX_BOUNDARY = 70;
	private static final int MAX_HEADERS = 16 * 1024;
	private static final byte[] CRLF = {'\r', '\n'};
	private static final byte DASH = '-';
	private static final String DISPOSITION = "Content-Disposition";
	private static final String FORM_DATA = "form-data";
	private static final Pattern PARAMETER = Pattern.compile(";\\s*([^\\s=;]+)\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))");

	private final InputStream input;
	private final byte[] delimiter;
	private final byte[] buffer = new byte[BUFFER];
	private int position;
	private int limit;
	private boolean closed;

	/* What comes before the first part, which is skipped like the rest of a part nobody read */
	private Body current = new Body();

	public record Part(String name, Optional<String> filename, Optional<String> contentType, InputStream body) { }

	/* The first delimiter opens the body instead of following a line, so the buffer starts with the line break */
	public FormData(final InputStream input, final String boundary) {
		if (Objects.isNull(boundary) || boundary.isEmpty() || boundary.length() > MAX_BOUNDARY) {
			throw malformed();
		}

		this.input = input;
		delimiter = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(CRLF, 0, buffer, 0, CRLF.length);
		limit = CRLF.length;
	}

	/* The boundary is a parameter of the multipart/form-data type of the request */
	public static FormData of(final InputStream input, final String contentType) {
		return new FormData(input, parameters(Objects.requireNonNullElse(contentType, "")).get("boundary"));
	}

	private static S3Exception malformed() {
		return new S3Exception(S3ErrorCode.MalformedPOSTRequest);
	}

	private final class Body extends InputStream {
		private boolean ended;

		@Override
		public int read() throws IOException {
			final byte[] single = new byte[1];

			return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
		}

		@Override
		public int read(final byte[] target, final int offset, final int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, target.length);

			if (ended || length == 0) {
				return ended ? -1 : 0;
			}

			final int read = data(target, offset, length);

			ended = read < 0;
			return read;
		}
	}

	public Optional<Part> next() throws IOException {
		current.transferTo(OutputStream.nullOutputStream());

		if (closed || closing()) {
			return Optional.empty();
		}

		final Map<String, String> headers = headers();
		final String disposition = headers.getOrDefault(DISPOSITION, "");

		if (!disposition.toLowerCase(Locale.ROOT).startsWith(FORM_DATA)) {
			throw malformed();
		}

		final Map<String, String> parameters = parameters(disposition.substring(FORM_DATA.length()));

		current = new Body();
		return Optional.of(new Part(Optional.ofNullable(parameters.get("name")).orElseThrow(FormData::malformed),
			Optional.ofNullable(parameters.get("filename")), Optional.ofNullable(headers.get("Content-Type")),
			current
		));
	}

	/* Part data up to the delimiter, keeping back the bytes that could begin it, or -1 once past the delimiter */
	private int data(final byte[] target, final int offset, final int length) throws IOException {
		fill(delimiter.length);

		if (limit - position < delimiter.length) {
			throw malformed();
		}

		final int window = Math.min(limit, position + length + delimiter.length - 1);
		final int found = indexOf(delimiter, window);

		if (found == position) {
			position += delimiter.length;
			return -1;
		}

		final int count = Math.min(length, (found < 0 ? window - delimiter.length + 1 : found) - position);

		System.arraycopy(buffer, position, target, offset, count);
		position += count;
		return count;
	}

	/* Two dashes after a delimiter close the body, and anything else but padding before the line ends is not form
	   data */
	private boolean closing() throws IOException {
		fill(2);

		if (limit - position >= 2 && buffer[position] == DASH && buffer[position + 1] == DASH) {
			closed = true;
			return true;
		}

		if (!line(MAX_HEADERS).isBlank()) {
			throw malformed();
		}

		return false;
	}

	private Map<String, String> headers() throws IOException {
		final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		int left = MAX_HEADERS;

		for (String line = line(left); !line.isEmpty(); line = line(left)) {
			final int colon = line.indexOf(':');

			if (colon <= 0) {
				throw malformed();
			}

			headers.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
			left -= line.length() + CRLF.length;
		}

		return headers;
	}

	private static Map<String, String> parameters(final String list) {
		final Map<String, String> parameters = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		final Matcher parameter = PARAMETER.matcher(list);

		while (parameter.find()) {
			parameters.put(parameter.group(1), Objects.requireNonNullElse(parameter.group(2),
				parameter.group(3)
			));
		}

		return parameters;
	}

	private String line(final int max) throws IOException {
		int found = indexOf(CRLF, Math.min(limit, position + max + CRLF.length));

		while (found < 0) {
			if (limit - position >= max + CRLF.length || !more()) {
				throw malformed();
			}

			found = indexOf(CRLF, Math.min(limit, position + max + CRLF.length));
		}

		final String line = new String(buffer, position, found - position, StandardCharsets.UTF_8);

		position = found + CRLF.length;
		return line;
	}

	/* Where the pattern starts between the position and the end, looking for its first byte before the rest */
	private int indexOf(final byte[] pattern, final int end) {
		for (int start = position; start + pattern.length <= end; start++) {
			if (buffer[start] == pattern[0] && Arrays.equals(buffer, start, start + pattern.length, pattern, 0,
				pattern.length)) {

				return start;
			}
		}

		return -1;
	}

	private boolean more() throws IOException {
		final int held = limit - position;

		fill(held + 1);
		return limit - position > held;
	}

	/* Reads until the bytes past the position are as many as needed, or the body ends */
	private void fill(final int needed) throws IOException {
		if (limit - position >= needed) {
			return;
		}

		System.arraycopy(buffer, position, buffer, 0, limit - position);
		limit -= position;
		position = 0;

		while (limit < needed) {
			final int read = input.read(buffer, limit, buffer.length - limit);

			if (read < 0) {
				return;
			}

			limit += read;
		}
	}
}
