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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

public class VirtualDirectory<T, A> extends VirtualAbstractPath<T, A> implements Cloneable {
	@Getter
	private final List<VirtualAbstractPath<T, A>> children = Collections.synchronizedList(new ArrayList<>());

	public VirtualDirectory(String separator, final String name, Supplier<String> rootPathSupplier) {
		super(separator, name, rootPathSupplier);
	}

	public VirtualDirectory(String separator, final String name, final VirtualDirectory<T, A> parent, Supplier<String> rootPathSupplier) {
		super(separator, name, parent, rootPathSupplier);
	}

	public void addChild(VirtualAbstractPath<T, A> p) {		
		children.add(p);		
		p.setParent(this);		
	}

	public void deleteChild(final String name) {
		for (VirtualAbstractPath mp : children) {
			if (mp.getName().equals(name)) {
				children.remove(mp);
				System.out.println(children);
			}
		}
	}

	public void deleteChild(final VirtualAbstractPath<T, A> child) {
		children.remove(child);
	}

	public Optional<VirtualAbstractPath<T, A>> find(String path, VirtualDirectory<T, A> directory) {

		final String[] names = path.split(Pattern.quote(getSeparator()));
		
		if (names.length <= 1) {
			return Optional.of(this);
		} else {
			for (VirtualAbstractPath<T, A> mp : children) {
				if (mp.getName().equals(names[1])) {
					final String newPath = String.join(getSeparator(), Arrays.copyOfRange(names, 1, names.length));
					return mp.find(newPath);
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public Optional<VirtualAbstractPath<T, A>> find(String path) {
		return find(path, this);
	}

	
	public void getattr(final A stat) {
		//TODO: Do we need it?
	}

	public void mkdir(final String lastComponent) {
		children.add(new VirtualDirectory(getSeparator(), lastComponent, this, getRootPathSupplier()));
	}

	public void mkfile(String lastComponent) {
		children.add(VirtualFile.create(getSeparator(), lastComponent, this, getRootPathSupplier()));
	}

	public static <T, A> VirtualDirectory<T, A> create(String separator, String fileName, Supplier<String> rootPathSupplier) {
		return new VirtualDirectory(separator, fileName, rootPathSupplier);
	}

	@Override
	public String toString() {
		String string = StringUtils.repeat('\t', getDepth()) + "Directory: " + getName() + "\n";

		for (VirtualAbstractPath<T, A> path : children) {
			string += path.toString();
		}
		return string;
	}

	@Override
	public Object clone() throws CloneNotSupportedException {
		return new VirtualDirectory(getSeparator(), getName(), getRootPathSupplier());
	}
	
	public boolean contains(String name){
		for (VirtualAbstractPath<T, A> child : children) {
			if (child.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}
}