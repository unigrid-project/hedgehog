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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.Validate;
import org.unigrid.hedgehog.model.crypto.Signature;

/**
* One change of the board keys: the keys that are active once it is made, which follow on the change before it, or on
* the root of the chain for the first, and the approvals of the board keys that made it so. Whether a change holds is
* for {@link BoardKeyChain} to judge, with the chain it stands in.
*/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyChange implements Serializable {
	private static final long serialVersionUID = -6043781937540212734L;

	/* Leads the bytes that an approval signs. A spork and a snapshot are signed over bytes that never start so, so no
	   signature of either passes for an approval, and no approval for either. */
	public static final byte[] TAG = "hedgehog/board-keys/change/1".getBytes(StandardCharsets.US_ASCII);

	public static final int DIGEST_SIZE = 64;

	/* A signature of P-521 takes up to 139 bytes */
	public static final int MAX_SIGNATURE_SIZE = 255;

	private static final Pattern KEY = Pattern.compile("[0-9a-f]{1," + 2 * Signature.PUBLIC_KEY_HEX_SIZE + "}");

	private int sequence;

	@JsonFormat(shape = JsonFormat.Shape.STRING)
	private Instant madeAt;

	private byte[] previous;
	private List<String> active = new ArrayList<>();
	private List<Approval> approvals = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Approval implements Serializable {
		private static final long serialVersionUID = 2304976118504263931L;

		private String key;
		private byte[] signature;
	}

	/*
	    Change format:
	    0..............................................................63
	    [           sequence           ][    made at (epoch ms)      >>
	    <<                             ][ << previous (64 bytes) >>  >>
	    [n=keys]
	   n[     size     ][               key (size long, hex)         >>]
	    [m=approvals]
	   m[     size     ][ key (size long, hex) >>][     size     ][ signature (size long) >>]
	*/
	public void write(ByteBuf out) {
		requireEncodable();
		writeSigned(out);
		out.writeByte(approvals.size());

		approvals.forEach(approval -> {
			writeSized(out, approval.getKey().getBytes(StandardCharsets.US_ASCII));
			writeSized(out, approval.getSignature());
		});
	}

	public byte[] toBytes() {
		return bytesOf(this::write);
	}

	/* The change without its approvals, behind the tag */
	@JsonIgnore
	public byte[] getSignable() {
		requireEncodable();

		return bytesOf(out -> {
			out.writeBytes(TAG);
			writeSigned(out);
		});
	}

	private void writeSigned(ByteBuf out) {
		out.writeInt(sequence);
		out.writeLong(madeAt.toEpochMilli());
		out.writeBytes(previous);
		out.writeByte(active.size());
		active.forEach(key -> writeSized(out, key.getBytes(StandardCharsets.US_ASCII)));
	}

	/* The counts and sizes travel in as few bytes as the caps need, so a change beyond them could only be written as
	   another change; it is refused instead */
	private void requireEncodable() {
		Validate.isTrue(Objects.nonNull(madeAt), "A change of the board keys has a time it was made at");
		Validate.isTrue(Objects.nonNull(previous) && previous.length == DIGEST_SIZE,
			"A change of the board keys follows a digest of %d bytes", DIGEST_SIZE);
		Validate.isTrue(Objects.nonNull(active) && active.size() <= BoardKeyChain.MAX_KEYS
			&& active.stream().allMatch(KeyChange::isEncodable), "A change of the board keys lists at most %d"
			+ " keys, each in lower-case hex", BoardKeyChain.MAX_KEYS);
		Validate.isTrue(Objects.nonNull(approvals) && approvals.size() <= BoardKeyChain.MAX_APPROVALS
			&& approvals.stream().allMatch(KeyChange::isEncodable), "A change of the board keys has at most %d"
			+ " approvals, each by a key in lower-case hex and with a signature", BoardKeyChain.MAX_APPROVALS);
	}

	private static boolean isEncodable(String key) {
		return Objects.nonNull(key) && KEY.matcher(key).matches();
	}

	private static boolean isEncodable(Approval approval) {
		return Objects.nonNull(approval) && isEncodable(approval.getKey())
			&& Objects.nonNull(approval.getSignature()) && approval.getSignature().length <= MAX_SIGNATURE_SIZE;
	}

	private static void writeSized(ByteBuf out, byte[] bytes) {
		out.writeShort(bytes.length);
		out.writeBytes(bytes);
	}

	private static byte[] bytesOf(Consumer<ByteBuf> writer) {
		final ByteBuf out = Unpooled.buffer();

		try {
			writer.accept(out);
			return ByteBufUtil.getBytes(out);
		} finally {
			out.release();
		}
	}
}
