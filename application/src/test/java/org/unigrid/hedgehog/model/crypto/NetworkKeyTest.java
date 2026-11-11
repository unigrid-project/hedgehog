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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.slf4j.LoggerFactory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

public class NetworkKeyTest extends BaseMockedWeldTest {
	private static final int NUM_SIGNATURES = 3;
	private static final List<Signature> SIGNATURES = new ArrayList<>(NUM_SIGNATURES);
	private static final List<Signature> EARLIER_SIGNATURES = new ArrayList<>(NUM_SIGNATURES);

	@SneakyThrows
	@BeforeProperty
	private void mockBefore() {
		for (int i = 0; i < NUM_SIGNATURES; i++) {
			SIGNATURES.add(new Signature());
			EARLIER_SIGNATURES.add(new Signature());
		}

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return publicKeys(SIGNATURES);
			}
		};
	}

	private static String[] publicKeys(List<Signature> signatures) {
		return signatures.stream().map(Signature::getPublicKey).toArray(String[]::new);
	}

	@SneakyThrows
	private static Signable signedBy(Signature signature, byte[] data) {
		final byte[] signatureData = signature.sign(data);

		return new Signable() {
			@Override public byte[] getSignable() {
				return data;
			}

			@Override public byte[] getSignature() {
				return signatureData;
			}

			@Override public void sign(String privateKeyHex) {
				throw new UnsupportedOperationException();
			}

			@Override public boolean isValidSignature() {
				throw new UnsupportedOperationException();
			}
		};
	}

	@Example
	@SneakyThrows
	public void storedPublicKeysShoulBeValid() {
		for (Signature signature : SIGNATURES) {
			assertThat(NetworkKey.isTrusted(signature.getPrivateKey()), is(true));
		}
	}

	@Property(tries = 10)
	public void randomPublicKeysShouldBeInvalid(@ForAll @Size(65) byte[] privateKey) {
		assertThat(NetworkKey.isTrusted(Hex.encodeHexString(privateKey)), is(false));
	}

	@Property(tries = 10)
	public void shouldNameTheTrustedSigner(@ForAll byte[] data) {
		final Signature signer = SIGNATURES.get(NUM_SIGNATURES - 1);

		assertThat(NetworkKey.signerOf(signedBy(signer, data)), is(Optional.of(signer.getPublicKey())));
	}

	@Property(tries = 10)
	public void shouldNameNoSignerForAKeyOfAnEarlierBoard(@ForAll byte[] data) {
		final Signable signable = signedBy(EARLIER_SIGNATURES.get(0), data);

		assertThat(NetworkKey.signerOf(signable), is(Optional.empty()));
		assertThat(NetworkKey.signerOf(DigestUtils.sha512(data), signable.getSignature()), is(Optional.empty()));
	}

	@SneakyThrows
	@Property(tries = 10)
	public void shouldNameNoSignerForUnknownKeys(@ForAll byte[] data) {
		assertThat(NetworkKey.signerOf(signedBy(new Signature(), data)), is(Optional.empty()));
	}

	@Property(tries = 10)
	public void shouldNameTheSignerOfADigest(@ForAll byte[] data) {
		final Signature signer = SIGNATURES.get(NUM_SIGNATURES - 1);
		final Signable signable = signedBy(signer, data);

		assertThat(NetworkKey.signerOf(DigestUtils.sha512(data), signable.getSignature()),
			is(Optional.of(signer.getPublicKey()))
		);
	}

	@Example
	public void shouldTakeLogEntriesOfTheCurrentKeysOnly() {
		assertThat(NetworkKey.getLogKeys(), is(Set.of(publicKeys(SIGNATURES))));
	}

	/* A signature that is no signature at all fails with an exception for each key it is tried against, and the trace
	   of that exception is for a node that logs at trace level, which the others must not pay for each time. The count
	   with the trace on shows that the signature does fail each key. */
	@Example
	public void shouldBuildNoTraceOfASignatureThatCannotBeReadUnlessTraceIsLogged() {
		final Logger logger = (Logger) LoggerFactory.getLogger(NetworkKey.class);
		final Level level = logger.getLevel();
		final MutableInt traces = new MutableInt();
		final byte[] digest = DigestUtils.sha512(new byte[] { 1 });
		final byte[] signature = new byte[] { 1, 2, 3 };

		new MockUp<ExceptionUtils>() {
			@Mock public String getStackTrace(Throwable throwable) {
				traces.increment();
				return "";
			}
		};

		try {
			logger.setLevel(Level.TRACE);
			assertThat(NetworkKey.signerOf(digest, signature), is(Optional.empty()));
			assertThat(traces.intValue(), is(SIGNATURES.size()));

			traces.setValue(0);
			logger.setLevel(Level.INFO);
			assertThat(NetworkKey.signerOf(digest, signature), is(Optional.empty()));
			assertThat(traces.intValue(), is(0));
		} finally {
			logger.setLevel(level);
		}
	}

	/* Anyone can send a signature that is no signature at all, and it fails with an exception for each key it is tried
	   against. The text of that exception holds the key, and is for a node that logs at trace level, or for whoever reads
	   the message, which the others must not pay for each time. The count with the trace on shows that the signature
	   does fail each key, and that the text logged is what it was. */
	@Example
	public void shouldWriteNoKeyAsTextForASignatureThatCannotBeReadUnlessTheMessageIsLogged() {
		final Logger logger = (Logger) LoggerFactory.getLogger(NetworkKey.class);
		final Level level = logger.getLevel();
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final byte[] digest = DigestUtils.sha512(new byte[] { 1 });
		final byte[] signature = new byte[] { 1, 2, 3 };

		appender.start();
		logger.addAppender(appender);

		try (CountedKeys keys = new CountedKeys(SIGNATURES)) {
			logger.setLevel(Level.INFO);
			assertThat(NetworkKey.signerOf(digest, signature), is(Optional.empty()));
			assertThat("keys written out as text", keys.getWritten().intValue(), is(0));
			assertThat(appender.list.size(), is(0));

			logger.setLevel(Level.TRACE);
			assertThat(NetworkKey.signerOf(digest, signature), is(Optional.empty()));
			assertThat(keys.getWritten().intValue(), is(SIGNATURES.size()));
			assertThat(appender.list.size(), is(SIGNATURES.size()));

			for (ECPublicKey key : keys.getReal().values()) {
				assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList(),
					hasItem(startsWith("Failed to verify siugnature data with public key '" + key + "':")));
			}
		} finally {
			logger.setLevel(level);
			logger.detachAppender(appender);
		}
	}
}
