/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

 package org.unigrid.hedgehog.model.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.jul.JULHelper;

import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import net.jqwik.api.ForAll;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import org.apache.commons.lang3.RandomStringUtils;

public class ApplicationLogLevelTest {

    private static final Logger log =
            Logger.getLogger(ApplicationLogLevelTest.class.getName());

    private static class JunitHandler extends Handler {

        private boolean dirty;

        public boolean isDirty() {
            return dirty;
        }

        public void setDirty(boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public void publish(LogRecord lr) {
            dirty = true;
        }

        @Override
        public void flush() { }

        @Override
        public void close() throws SecurityException { }
    }

    @Property(tries = 10)
    public boolean shouldOutputLogMessagesByLogLevel(
            @ForAll @IntRange(min = 1, max = 5) int logLevel,
            @ForAll @IntRange(min = 0, max = 6) int messageLevel) {

        final java.util.logging.Level level =
                JULHelper.asJULLevel(
                        ApplicationLogLevel.getLevelFromVerbosity(messageLevel)
                );

        ApplicationLogLevel.configure(logLevel);

        final JunitHandler handler = new JunitHandler();
        log.addHandler(handler);

        log.log(level, RandomStringUtils.randomAscii(8));

        if (handler.isDirty()) {
            return messageLevel <= logLevel;
        } else {
            return messageLevel > logLevel;
        }
    }

    @Example
    public boolean shouldThrowExceptionOnUnsupportedLevel() {

        try {
            ApplicationLogLevel.getVerbosityFromLevel(Level.ALL);
        } catch (UnsupportedLogLevelException ex) {
            return true;
        }

        return false;
    }
} 