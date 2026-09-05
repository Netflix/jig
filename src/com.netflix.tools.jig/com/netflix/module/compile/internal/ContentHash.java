/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.module.compile.internal;

import java.io.DataOutput;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** A SHA-256 value without an implied storage or semantic role. */
public final class ContentHash implements Comparable<ContentHash> {
    public static final int LENGTH = 32;
    private final byte[] bytes;

    public ContentHash(byte[] bytes) {
        this(bytes, true);
    }

    private ContentHash(byte[] bytes, boolean copy) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != LENGTH) {
            throw new IllegalArgumentException("SHA-256 value must contain " + LENGTH + " bytes");
        }
        this.bytes = copy ? bytes.clone() : bytes;
    }

    static ContentHash takeOwnership(byte[] bytes) {
        return new ContentHash(bytes, false);
    }

    public static ContentHash sha256(byte[] content) {
        Objects.requireNonNull(content, "content");
        return takeOwnership(newDigest().digest(content));
    }

    public static ContentHash fromHex(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() != LENGTH * 2) {
            throw new IllegalArgumentException("SHA-256 value must contain " + (LENGTH * 2) + " hexadecimal digits");
        }
        return takeOwnership(HexFormat.of()
                .parseHex(encoded));
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    void writeTo(DataOutput output) throws IOException {
        output.write(bytes);
    }

    public void update(MessageDigest digest) {
        Objects.requireNonNull(digest, "digest").update(bytes);
    }

    public String hex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public int compareTo(ContentHash other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentHash hash && Arrays.equals(bytes, hash.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return hex();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
