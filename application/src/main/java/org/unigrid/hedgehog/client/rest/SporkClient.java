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

package org.unigrid.hedgehog.client.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysAuthority;
import org.unigrid.hedgehog.model.spork.BoardKeysRecord;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.RecordedChainException;

/* The sporks of a node as a board member's command line reads them and hands them back: in the form peers exchange, so
   that what is signed on this machine covers the bytes every node reads. The client trusts any certificate, and the
   node may not be what it claims to be either, so nothing it hands out is signed on top of before it is known to be
   what the network took or what a current board key proposed. A spork that does not decode is an
   IllegalArgumentException, as it would be from a peer. */
@RequiredArgsConstructor
public class SporkClient {
	public static final String WIRE = "/gridspork/wire";
	public static final String PENDING = "/gridspork/pending";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String AHEAD = "stamped ahead of the clock of this machine";
	private static final int SHOWN_HEX = 16;
	private static final String SWITCHED_OFF = " (switched off)";
	private static final String UNROOTED = "The board keys that the node holds do not hold from the root of this"
		+ " machine, or two of their keys did not sign them (see --network-keys)";

	private final RestClient rest;

	/**
	* Goes by the board keys that the node holds, once they hold from the root of this machine: the keys that sign a
	* spork are those that the chain of the board makes active, and a key that the board added since the release is
	* one of them. A node that holds none leaves the keys built into the release. Board keys that do not hold, or that
	* two of their own keys did not sign, sign nothing on this machine, and nor do any that hold less than the chain that
	* this machine verified before ({@link BoardKeysRecord}).
	*/
	public void installBoardKeys() throws ResponseOddityException, UntrustedSporkException, IOException {
		BoardKeysAuthority.installTheRelease();

		final Optional<GridSpork> held = sporkIn(rest.get(WIRE + "/" + GridSpork.Type.BOARD_KEYS.name()));

		if (held.isPresent() && !(held.get() instanceof BoardKeys chain && chain.isTakenByTheNetwork())) {
			throw new UntrustedSporkException(UNROOTED);
		}

		final Optional<BoardKeys> boardKeys = held.map(BoardKeys.class::cast);

		try {
			BoardKeysRecord.ofThisMachine().keep(boardKeys.map(spork -> spork.<BoardKeys.SporkData>getData()
				.getChanges()), "the node").ifPresent(System.out::println);
		} catch (RecordedChainException ex) {
			throw new UntrustedSporkException(ex.getMessage());
		}

		boardKeys.ifPresent(BoardKeysAuthority::install);
	}

	/* The stored spork of a type, once it is known to be one the network took and that two keys active now signed. One
	   that a key signed which the board switched off since counts for nothing, and the node may hand out one that two
	   stolen keys forged, so nothing is built on it; it is renewed first, by name (held()). */
	public Optional<GridSpork> stored(GridSpork.Type type) throws ResponseOddityException, UntrustedSporkException,
		IOException {

		final Optional<GridSpork> stored = held(type);

		if (stored.isPresent() && !stored.get().isTakenByTheNetwork()) {
			throw new UntrustedSporkException("The " + type + " spork that the node holds is "
				+ signingOf(stored.get()) + " and counts for nothing; renew it first with gridspork-renew"
				+ " --switched-off " + type + ", as nothing is signed on top of it");
		}

		return stored;
	}

	/**
	* The stored spork of a type, once it is known to be one the network took, by two keys that are active or that the
	* board switched off since ({@link GridSpork#wasTakenByTheNetwork()}): what the commands that sign a spork again
	* read, which renew one of the latter only once its type is named. One stamped further ahead than clocks drift
	* apart would make the next version as far ahead, which no node takes.
	*/
	public Optional<GridSpork> held(GridSpork.Type type) throws ResponseOddityException, UntrustedSporkException,
		IOException {

		final Optional<GridSpork> stored = sporkIn(rest.get(WIRE + "/" + type.name()));

		if (stored.isPresent() && (stored.get().getType() != type || !stored.get().wasTakenByTheNetwork())) {
			throw new UntrustedSporkException("The " + type + " spork that the node holds is not one that board "
				+ "keys signed, so nothing is signed on top of it");
		}

		if (stored.isPresent() && isAheadOfTheClock(stored.get().getTimeStamp())) {
			throw new UntrustedSporkException("The " + type + " spork that the node holds is " + AHEAD
				+ ", made at " + stored.get().getTimeStamp() + ", so nothing is signed on top of it");
		}

		return stored;
	}

	/* The keys that signed a spork, by the start of each, and which of them the board switched off */
	public static String signingOf(GridSpork spork) {
		final Set<String> active = NetworkKey.getActiveKeys().active();

		return "signed by " + signersOf(spork).stream().map(signer -> signer.map(key -> StringUtils.left(key,
			SHOWN_HEX) + (active.contains(key) ? "" : SWITCHED_OFF)).orElse("no board key"))
			.collect(Collectors.joining(" and "));
	}

	/* Of each signature of the head, the key that was ever a board key and made it */
	public static List<Optional<String>> signersOf(GridSpork spork) {
		final byte[] digest = DigestUtils.sha512(spork.getSignable());

		return Stream.of(spork.getSignature(), spork.getCosignature()).filter(Objects::nonNull)
			.map(signature -> NetworkKey.signerOf(digest, signature, NetworkKey.getLogKeys())).toList();
	}

	/* A proposal as the node hands it out, which is for the caller to judge with requireProposalOver() */
	public Optional<GridSpork> proposal(String digest) throws ResponseOddityException, IOException {
		return sporkIn(rest.get(PENDING + "/" + digest));
	}

	/* The node lists its proposals with their digests, by which it hands each one out. The one of the type is taken
	   only once it is known to be what a current board key made from the stored spork, which is a verified one. */
	public Optional<GridSpork> proposalOf(GridSpork.Type type, GridSpork stored) throws ResponseOddityException,
		IOException, UntrustedSporkException {

		final Response listed = rest.get(PENDING);

		if (listed.getStatus() != Status.OK.getStatusCode()) {
			return Optional.empty();
		}

		for (JsonNode listing : JSON.readTree(bodyOf(listed))) {
			if (type.name().equals(listing.path("type").asText())) {
				final Optional<GridSpork> proposal = proposal(listing.path("digest").asText());

				if (proposal.isPresent()) {
					requireProposalOver(proposal.get(), type, stored);
				}

				return proposal;
			}
		}

		return Optional.empty();
	}

	/* An honest node drops a proposal once it has waited its lifetime and takes none stamped further ahead than clocks
	   drift apart, so one outside that span is an abandoned proposal handed out again, or a forged one */
	public static void requireProposalOver(GridSpork proposal, GridSpork.Type type, GridSpork stored)
		throws UntrustedSporkException {

		if (proposal.getType() != type || !proposal.isProposalOver(stored)) {
			throw new UntrustedSporkException("The " + type + " proposal that the node waits on is not one that "
				+ "a current board key made from the spork it holds, so nothing is signed on top of it");
		}

		final Instant made = proposal.getTimeStamp();
		final boolean isAhead = isAheadOfTheClock(made);

		if (isAhead || made.isBefore(Instant.now().minus(PendingSporks.LIFETIME))) {
			throw new UntrustedSporkException("The " + type + " proposal that the node waits on was made at "
				+ made + ", " + (isAhead ? AHEAD : "longer ago than a proposal waits")
				+ ", so nothing is signed on top of it");
		}
	}

	private static boolean isAheadOfTheClock(Instant stamp) {
		return stamp.isAfter(Instant.now().plus(PendingSporks.LEEWAY));
	}

	public Response submit(GridSpork spork) throws ResponseOddityException {
		return rest.post(WIRE, Entity.entity(SporkWire.encode(spork), MediaType.APPLICATION_OCTET_STREAM));
	}

	private static Optional<GridSpork> sporkIn(Response response) throws IOException {
		if (response.getStatus() != Status.OK.getStatusCode()) {
			return Optional.empty();
		}

		return Optional.of(SporkWire.decode(bodyOf(response)));
	}

	/* What the node answers a signed spork with: the proposal it holds, or why it refused the spork */
	public static String textOf(Response response) throws IOException {
		return new String(bodyOf(response), StandardCharsets.UTF_8);
	}

	/* A node answers with a spork or with the list of its proposals, one of each type, so an answer bigger than a
	   spork is refused once that much is read, before any of it is decoded */
	private static byte[] bodyOf(Response response) throws IOException {
		try (InputStream body = response.readEntity(InputStream.class)) {
			final byte[] bytes = body.readNBytes(SporkWire.MAX_SIZE + 1);

			if (bytes.length > SporkWire.MAX_SIZE) {
				throw new IllegalArgumentException("The node answered with more than " + SporkWire.MAX_SIZE
					+ " bytes, which no spork takes");
			}

			return bytes;
		}
	}
}
