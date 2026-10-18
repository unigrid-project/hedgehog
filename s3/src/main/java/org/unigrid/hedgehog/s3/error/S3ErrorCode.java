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

import jakarta.ws.rs.core.Response.Status;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/* Named exactly as the codes S3 clients match on, so the name of a constant is its code */
@Getter
@RequiredArgsConstructor
public enum S3ErrorCode {
	AccessDenied(Status.FORBIDDEN, "Access Denied"),
	AuthorizationHeaderMalformed(Status.BAD_REQUEST, "The authorization header that you provided is not valid."),
	AuthorizationQueryParametersError(Status.BAD_REQUEST,
		"The authorization query parameters that you provided are not valid."),
	BadDigest(Status.BAD_REQUEST,
		"The Content-MD5 or checksum value that you specified did not match what the server received."),
	BucketAlreadyOwnedByYou(Status.CONFLICT, "The bucket that you tried to create already exists, and you own it."),
	BucketNotEmpty(Status.CONFLICT, "The bucket that you tried to delete is not empty."),
	EntityTooLarge(Status.BAD_REQUEST, "Your proposed upload exceeds the maximum allowed size."),
	EntityTooSmall(Status.BAD_REQUEST, "Your proposed upload is smaller than the minimum allowed object size."),
	IncompleteBody(Status.BAD_REQUEST,
		"You did not provide the number of bytes specified by the Content-Length HTTP header."),
	InternalError(Status.INTERNAL_SERVER_ERROR, "We encountered an internal error. Please try again."),
	InvalidAccessKeyId(Status.FORBIDDEN, "The AWS access key ID that you provided does not exist in our records."),
	InvalidArgument(Status.BAD_REQUEST, "Invalid Argument"),
	InvalidBucketName(Status.BAD_REQUEST, "The specified bucket is not valid."),
	InvalidDigest(Status.BAD_REQUEST, "The Content-MD5 or checksum value that you specified is not valid."),
	InvalidPart(Status.BAD_REQUEST, "One or more of the specified parts could not be found."),
	InvalidPartOrder(Status.BAD_REQUEST, "The list of parts was not in ascending order."),
	InvalidRange(Status.REQUESTED_RANGE_NOT_SATISFIABLE, "The requested range cannot be satisfied."),
	InvalidRequest(Status.BAD_REQUEST, "Invalid Request"),
	KeyTooLongError(Status.BAD_REQUEST, "Your key is too long."),
	MalformedTrailerError(Status.BAD_REQUEST, "The request contained trailing data that was not well-formed or "
		+ "did not conform to our published schema."),
	MalformedXML(Status.BAD_REQUEST, "The XML that you provided was not well formed or did not validate."),
	MethodNotAllowed(Status.METHOD_NOT_ALLOWED, "The specified method is not allowed against this resource."),
	MissingContentLength(Status.LENGTH_REQUIRED, "You must provide the Content-Length HTTP header."),
	NoSuchBucket(Status.NOT_FOUND, "The specified bucket does not exist."),
	NoSuchKey(Status.NOT_FOUND, "The specified key does not exist."),
	NoSuchUpload(Status.NOT_FOUND, "The specified multipart upload does not exist."),
	NoSuchVersion(Status.NOT_FOUND, "The version ID specified in the request does not match an existing version."),
	NotImplemented(Status.NOT_IMPLEMENTED, "A header or query you provided implies functionality that is not "
		+ "implemented."),
	NotModified(Status.NOT_MODIFIED, "Not Modified"),
	PreconditionFailed(Status.PRECONDITION_FAILED, "At least one of the preconditions that you specified did not "
		+ "hold."),
	RequestTimeTooSkewed(Status.FORBIDDEN, "The difference between the request time and the server's time is too "
		+ "large."),
	ServiceUnavailable(Status.SERVICE_UNAVAILABLE, "Service is unable to handle request."),
	SignatureDoesNotMatch(Status.FORBIDDEN, "The request signature that the server calculated does not match the "
		+ "signature that you provided. Check your secret access key and signing method."),
	XAmzContentSHA256Mismatch(Status.BAD_REQUEST, "The provided 'x-amz-content-sha256' header does not match what "
		+ "was computed.");

	private final Status status;
	private final String message;
}
