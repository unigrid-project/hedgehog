/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
*/

package org.unigrid.hedgehog.filesystem;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class VirtualAbstractPath<T, A> {
	private final String separator;
	@NonNull private String name;
	private VirtualTimeInfo timeInfo = VirtualTimeInfo.builder().build();
	private VirtualDirectory parent;
	private T userData;
	private final Supplier<String> rootPathSupplier;

	protected VirtualAbstractPath(String separator, String name, VirtualDirectory parent, T userData, Supplier<String> rootPathSupplier) {
		this(separator, name, parent, rootPathSupplier);
		this.userData = userData;
	}

	protected VirtualAbstractPath(String separator, String name, VirtualDirectory parent, Supplier<String> rootPathSupplier) {
		this(separator, name, rootPathSupplier);
		this.parent = parent;
	}

	public void delete() {
		if (parent != null) {
			parent.deleteChild(this);
			parent = null;
		}
		
	}

	public Path getPath() {
		final List<String> paths = new ArrayList<>();
		VirtualAbstractPath p = this;
		while (p != null) {
			paths.add(0, p.getName());
			p = p.getParent();
		}
		return Path.of(rootPathSupplier.get(), paths.toArray(new String[0]));
	}

	public abstract Optional<VirtualAbstractPath<T, A>> find(String path);
	public abstract void getattr(A stat);

	public void rename(String newName) {
		while (newName.startsWith(separator)) {
			newName = newName.substring(1);
		}
		name = newName;
	}

	public int getDepth() {
		VirtualAbstractPath path = this;
		int depth = 0;

		while(path.getParent() != null) {
			depth++;
			path = path.getParent();
		}

		return depth;
	}

	public long getFolderSize() {
		int size = 0;
		
		if (this instanceof VirtualDirectory) {
			for (VirtualAbstractPath mp : ((VirtualDirectory<T, A>) this).getChildren()) {
				size += mp.getFolderSize();
			}
		} else if (this instanceof VirtualFile) {
			size += ((VirtualFile<T, A>) this).getSize();
		}
		return size;
	}
	
	public static String getLastComponent(String separator, String path) {
		while (path.substring(path.length() - 1).equals(separator)) {
			path = path.substring(0, path.length() - 1);
		}

		if (path.isEmpty()) {
			return "";
		}
		return path.substring(path.lastIndexOf(separator) + 1);
	}
}