/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
*/

 package org.unigrid.hedgehog.common.model;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ApplicationDirectory {

    private final String author;
    private final String name;

    // Konstruktor
    public ApplicationDirectory(String author, String name) {
        this.author = author;
        this.name = name;
    }

    // Getter
    public String getAuthor() {
        return author;
    }

    public String getName() {
        return name;
    }

    // Directory-metoder (enkelt alternativ utan AppDirs)
    public Path getUserCacheDir() {
        return Paths.get(System.getProperty("user.home"), "." + name, "cache");
    }

    public Path getUserConfigDir() {
        return Paths.get(System.getProperty("user.home"), "." + name, "config");
    }

    public Path getUserDataDir() {
        return Paths.get(System.getProperty("user.home"), "." + name, "data");
    }

    public Path getUserLogDir() {
        return Paths.get(System.getProperty("user.home"), "." + name, "logs");
    }

    // Fabrikmetod
    public static ApplicationDirectory create() {
        String author = Version.getAuthor();
        String name = Version.getName();

        // Om Unix, gör små bokstäver
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("nix") || os.contains("nux")) {
            author = author.toLowerCase();
            name = name.toLowerCase();
        }

        return new ApplicationDirectory(author, name);
    }
}