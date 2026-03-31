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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

public class Version {

    private static final String DEFAULT_AUTHOR = "Unigrid";
    private static final String DEFAULT_NAME = "Hedgehog";
    private static final String DEFAULT_VERSION = "0.0.0-BASTARD";

    public String[] getVersion() {
        Properties properties = new Properties();
        String name = DEFAULT_NAME;
        String author = DEFAULT_AUTHOR;
        String version = DEFAULT_VERSION;

        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
                author = Objects.requireNonNullElse(properties.getProperty("project.author"), DEFAULT_AUTHOR);
                name = Objects.requireNonNullElse(properties.getProperty("project.name"), DEFAULT_NAME);
                version = Objects.requireNonNullElse(properties.getProperty("project.version"), DEFAULT_VERSION);
            }
        } catch (IOException ex) {
            System.err.println("Unable to load version properties: " + ex.getMessage());
        }

        String completeVersion = author + " " + name + " " + version;
        return new String[] { completeVersion };
    }

    private static String getAtIndex(int i) {
        String[] parts;
        try {
            parts = new Version().getVersion()[0].split(" ");
            return parts[i];
        } catch (Exception e) {
            if (i == 0) return DEFAULT_AUTHOR;
            if (i == 1) return DEFAULT_NAME;
            return DEFAULT_VERSION;
        }
    }

    public static String getAuthor() {
        return getAtIndex(0);
    }

    public static String getName() {
        return getAtIndex(1);
    }

    public static String getVersionNumber() {
        return getAtIndex(2);
    }
}