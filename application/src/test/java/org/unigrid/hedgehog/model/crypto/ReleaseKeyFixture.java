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

package org.unigrid.hedgehog.model.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.List;
import lombok.Cleanup;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDownload;

/* A release key made up for one test, so assets can be published without the foundation's secret key. */
public final class ReleaseKeyFixture {
	private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();

	private final PGPKeyPair pair;

	@SneakyThrows
	private ReleaseKeyFixture() {
		final KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519", PROVIDER);

		pair = new JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.EDDSA_LEGACY,
			generator.generateKeyPair(), new Date());
	}

	/* The key the build trusts as its release key until the next fixture takes its place. */
	public static ReleaseKeyFixture trusted() {
		final ReleaseKeyFixture fixture = new ReleaseKeyFixture();

		new MockUp<ReleaseKey>() {
			@Mock public PGPPublicKeyRingCollection getPublicKeyRing() {
				return fixture.ring();
			}
		};

		return fixture;
	}

	public static ReleaseKeyFixture foreign() {
		return new ReleaseKeyFixture();
	}

	@SneakyThrows
	public PGPPublicKeyRingCollection ring() {
		return new PGPPublicKeyRingCollection(List.of(new PGPPublicKeyRing(List.of(pair.getPublicKey()))));
	}

	@SneakyThrows
	public byte[] sign(byte[] data) {
		final PGPSignatureGenerator generator = new PGPSignatureGenerator(new JcaPGPContentSignerBuilder(
			pair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256).setProvider(PROVIDER), pair.getPublicKey());
		final ByteArrayOutputStream armored = new ByteArrayOutputStream();

		generator.init(PGPSignature.BINARY_DOCUMENT, pair.getPrivateKey());
		generator.update(data);

		@Cleanup final ArmoredOutputStream armor = new ArmoredOutputStream(armored);

		generator.generate().encode(armor);
		armor.close();
		return armored.toByteArray();
	}

	/* Publishes the hash of the asset beside it, the way a release does. */
	@SneakyThrows
	public void publish(Path asset) {
		publishHash(asset, DigestUtils.sha256Hex(Files.readAllBytes(asset)) + "  " + asset.getFileName() + "\n");
	}

	@SneakyThrows
	public void publishHash(Path asset, String hashFile) {
		final byte[] contents = hashFile.getBytes(StandardCharsets.US_ASCII);

		Files.write(sibling(asset, SnapshotDownload.HASH_SUFFIX), contents);
		Files.write(sibling(asset, SnapshotDownload.HASH_SIGNATURE_SUFFIX), sign(contents));
	}

	private static Path sibling(Path asset, String suffix) {
		final Path sibling = asset.resolveSibling(asset.getFileName() + suffix);

		sibling.toFile().deleteOnExit();
		return sibling;
	}
}
