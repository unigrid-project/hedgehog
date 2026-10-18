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

package org.unigrid.hedgehog.s3.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* What a namespace should hold, kept the plain way: keys in UTF-8 byte order, versions newest first, uploads oldest
   first, and listings computed from the whole ordered contents and then cut down to the page asked for */
public final class NamespaceModel {
	public static final Comparator<String> UTF8 = Comparator.comparing(text -> text.getBytes(StandardCharsets.UTF_8),
		Arrays::compareUnsigned);

	/* A listed entry, or a common prefix when the entry is null */
	private record Row<T>(String name, String id, T entry) {
	}

	public record Page<T>(List<T> entries, List<String> prefixes, Optional<Marker> next) {
	}

	private final NavigableMap<String, Bucket> buckets = new TreeMap<>(UTF8);
	private final Map<String, NavigableMap<String, List<ObjectVersion>>> versions = new HashMap<>();
	private final Map<String, NavigableMap<String, List<Upload>>> uploads = new HashMap<>();
	private final Map<String, NavigableMap<Integer, Part>> parts = new HashMap<>();

	public Optional<Bucket> bucket(final String name) {
		return Optional.ofNullable(buckets.get(name));
	}

	public List<Bucket> buckets() {
		return List.copyOf(buckets.values());
	}

	public void putBucket(final Bucket bucket) {
		buckets.put(bucket.name(), bucket);
	}

	public void removeBucket(final String name) {
		buckets.remove(name);
	}

	public boolean isEmpty(final String bucket) {
		return versions(bucket).isEmpty() && uploads(bucket).isEmpty();
	}

	public NavigableMap<String, List<ObjectVersion>> versions(final String bucket) {
		return versions.computeIfAbsent(bucket, name -> new TreeMap<>(UTF8));
	}

	private NavigableMap<String, List<Upload>> uploads(final String bucket) {
		return uploads.computeIfAbsent(bucket, name -> new TreeMap<>(UTF8));
	}

	public Optional<ObjectVersion> latest(final String bucket, final String key) {
		return versions(bucket).getOrDefault(key, List.of()).stream().findFirst();
	}

	public Optional<ObjectVersion> version(final String bucket, final String key, final String versionId) {
		return versions(bucket).getOrDefault(key, List.of()).stream()
			.filter(version -> version.versionId().equals(versionId)).findFirst();
	}

	/* The id of an existing version picked by the number, or of the null version when the key has none */
	public String pickVersion(final String bucket, final String key, final int pick) {
		final List<ObjectVersion> list = versions(bucket).getOrDefault(key, List.of());
		return list.isEmpty() ? ObjectVersion.NULL_VERSION : list.get(Math.floorMod(pick, list.size())).versionId();
	}

	public void put(final ObjectVersion stored) {
		final List<ObjectVersion> list = versions(stored.bucket())
			.computeIfAbsent(stored.key(), key -> new ArrayList<>());

		if (stored.isNullVersion()) {
			list.removeIf(ObjectVersion::isNullVersion);
		}

		list.addFirst(stored);
	}

	public void replace(final ObjectVersion changed) {
		final List<ObjectVersion> list = versions(changed.bucket()).get(changed.key());

		list.replaceAll(version -> version.versionId().equals(changed.versionId()) ? changed : version);
	}

	public void remove(final String bucket, final String key, final String versionId) {
		final List<ObjectVersion> list = versions(bucket).getOrDefault(key, new ArrayList<>());

		list.removeIf(version -> version.versionId().equals(versionId));

		if (list.isEmpty()) {
			versions(bucket).remove(key);
		}
	}

	/* Every blob a version or a part of an upload refers to */
	public Set<BlobId> blobs() {
		final Set<BlobId> blobs = new HashSet<>();

		versions.values().forEach(keys -> keys.values().forEach(list -> list.forEach(version ->
			blobs.addAll(version.blobs()))));
		parts.values().forEach(numbered -> numbered.values().forEach(part -> blobs.addAll(part.blobs())));
		return blobs;
	}

	public List<Upload> uploads() {
		return uploads.values().stream().flatMap(keys -> keys.values().stream()).flatMap(List::stream).toList();
	}

	public Optional<Upload> pickUpload(final int pick) {
		final List<Upload> all = uploads();
		return all.isEmpty() ? Optional.empty() : Optional.of(all.get(Math.floorMod(pick, all.size())));
	}

	public void addUpload(final Upload upload) {
		uploads(upload.bucket()).computeIfAbsent(upload.key(), key -> new ArrayList<>()).add(upload);
	}

	public NavigableMap<Integer, Part> parts(final String uploadId) {
		return parts.computeIfAbsent(uploadId, id -> new TreeMap<>());
	}

	public void dropUpload(final Upload upload) {
		final List<Upload> list = uploads(upload.bucket()).get(upload.key());

		list.remove(upload);
		parts.remove(upload.uploadId());

		if (list.isEmpty()) {
			uploads(upload.bucket()).remove(upload.key());
		}
	}

	public Page<ObjectVersion> listObjects(final String bucket, final ListQuery query) {
		final List<Row<ObjectVersion>> rows = new ArrayList<>();

		versions(bucket).forEach((key, list) -> {
			if (!list.getFirst().deleteMarker()) {
				rows.add(new Row<>(key, null, list.getFirst()));
			}
		});

		return page(rows, query.toBuilder().idMarker("").build());
	}

	public Page<VersionEntry> listVersions(final String bucket, final ListQuery query) {
		final List<Row<VersionEntry>> rows = new ArrayList<>();

		versions(bucket).forEach((key, list) -> {
			for (int i = 0; i < list.size(); i++) {
				rows.add(new Row<>(key, list.get(i).versionId(), new VersionEntry(list.get(i), i == 0)));
			}
		});

		return page(rows, query);
	}

	public Page<Upload> listUploads(final String bucket, final ListQuery query) {
		final List<Row<Upload>> rows = new ArrayList<>();

		uploads(bucket).forEach((key, list) -> list.forEach(upload -> rows.add(new Row<>(key, upload.uploadId(),
			upload))));

		return page(rows, query);
	}

	public Page<Part> listParts(final Upload upload, final int marker, final int limit) {
		final List<Part> after = List.copyOf(parts(upload.uploadId()).tailMap(marker, false).values());
		final List<Part> page = after.subList(0, Math.min(Math.max(limit, 0), after.size()));
		final boolean truncated = limit > 0 && after.size() > limit;

		return new Page<>(page, List.of(), truncated ? Optional.of(new Marker(upload.key(),
			String.valueOf(page.getLast().number()))) : Optional.empty());
	}

	private static <T> Page<T> page(final List<Row<T>> rows, final ListQuery query) {
		if (query.limit() <= 0) {
			return new Page<>(List.of(), List.of(), Optional.empty());
		}

		final List<Row<T>> remaining = afterMarker(rolledUp(rows, query), query);
		final List<Row<T>> page = remaining.subList(0, Math.min(query.limit(), remaining.size()));
		final Optional<Marker> next = remaining.size() > query.limit()
			? Optional.of(new Marker(page.getLast().name(), page.getLast().id())) : Optional.empty();

		return new Page<>(page.stream().map(Row::entry).filter(Objects::nonNull).toList(),
			page.stream().filter(row -> Objects.isNull(row.entry())).map(Row::name).toList(), next);
	}

	private static <T> List<Row<T>> rolledUp(final List<Row<T>> rows, final ListQuery query) {
		final List<Row<T>> rolled = new ArrayList<>();

		for (Row<T> row : rows) {
			final int at = query.delimiter().isEmpty() ? -1
				: row.name().indexOf(query.delimiter(), query.prefix().length());
			final Row<T> listed = at < 0 ? row
				: new Row<>(row.name().substring(0, at + query.delimiter().length()), null, null);

			final boolean repeated = !rolled.isEmpty() && samePrefix(rolled.getLast(), listed);

			if (row.name().startsWith(query.prefix()) && !repeated) {
				rolled.add(listed);
			}
		}

		return rolled;
	}

	private static <T> boolean samePrefix(final Row<T> first, final Row<T> second) {
		return Objects.isNull(first.entry()) && Objects.isNull(second.entry()) && first.name().equals(second.name());
	}

	/* Common prefixes up to the key marker are done with, and so are entries before it, while the entries of the
	   marker key itself only continue after the one the id marker names */
	private static <T> List<Row<T>> afterMarker(final List<Row<T>> rows, final ListQuery query) {
		if (query.keyMarker().isEmpty()) {
			return rows;
		}

		int marker = -1;

		for (int i = 0; i < rows.size(); i++) {
			final Row<T> row = rows.get(i);

			if (Objects.nonNull(row.entry()) && row.name().equals(query.keyMarker())
				&& Objects.equals(row.id(), query.idMarker())) {

				marker = i;
			}
		}

		final List<Row<T>> kept = new ArrayList<>();

		for (int i = 0; i < rows.size(); i++) {
			final int order = UTF8.compare(rows.get(i).name(), query.keyMarker());
			final boolean resumed = Objects.nonNull(rows.get(i).entry()) && order == 0 && marker >= 0
				&& i > marker;

			if (order > 0 || resumed) {
				kept.add(rows.get(i));
			}
		}

		return kept;
	}
}
