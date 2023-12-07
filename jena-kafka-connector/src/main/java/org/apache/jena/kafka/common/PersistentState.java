/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jena.kafka.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files ;
import java.nio.file.Path;
import java.nio.file.Paths ;
import java.util.Objects;

import org.apache.jena.atlas.io.IOX;
import org.apache.jena.kafka.refs.RefBytes;
import org.apache.jena.kafka.refs.RefLong;
import org.apache.jena.kafka.refs.RefString;

/**
 * Persistent bytes, with some additional code to make
 * working with strings/integers/bytes easier.
 *
 * "State" is a number of bytes, assumed to be "small", (it makes sense to read and write the whole state as one blob).
 *
 * Updates to the state are atomic.
 */
public class PersistentState implements RefBytes, RefString, RefLong {
    private static final long DEFAULT = 0;
    private final Object lock = new Object();
    private final Path pathname;

    private byte[] value = null;
    private String valueStr = null;

    public static PersistentState createEphemeral() {
        return new PersistentState();
    }

    public PersistentState(String stateFile) {
        this(Paths.get((stateFile)));
    }

    public PersistentState(Path stateFilePath) {
        Objects.requireNonNull(stateFilePath);
        pathname = stateFilePath.toAbsolutePath();
        reset();
    }

    // Ephemeral
    private PersistentState() {
        pathname = null;
        reset();
    }

    public Path getPath() { return pathname; }

    public void reset() {
        if ( pathname != null && Files.exists(pathname) ) {
            value = readLocation();
            valueStr = null;
        } else {
            value = new byte[0];
            valueStr = null;
            writeLocation();
        }
    }

    @Override
    public byte[] getBytes() {
        return value ;
    }

    @Override
    public void setBytes(byte[] x) {
        value = x ;
        valueStr = null;
        writeLocation();
    }

    // Present the byte buffer as a String.
    @Override
    public String getString() {
        if ( valueStr == null )
            valueStr = new String(value,  StandardCharsets.UTF_8);
        return valueStr ;
    }

    @Override
    public void setString(String str) {
        setBytes(str.getBytes(StandardCharsets.UTF_8));
    }

    // Present the byte buffer as a Long
    @Override
    public long getInteger() {
        String s = getString();
        if ( s.isEmpty() )
            return DEFAULT;
        return Long.parseLong(getString().trim());
    }

    @Override
    public void setInteger(long value) {
        setString(Long.toString(value));
    }

    // increment and return the new value.
    @Override
    public long inc() {
        synchronized(lock) {
            long x = getInteger();
            x++;
            setInteger(x);
            return x ;
        }
    }

    // The access to on-disk (or not) bytes.

    private byte[] readLocation() {
        if ( pathname == null )
            return null;
        return IOX.readAll(pathname) ;
    }

    private void writeLocation() {
        if ( pathname == null )
            return;
        // Does not need synchronizing.
        IOX.safeWrite(pathname, out-> out.write(value));
    }
}
