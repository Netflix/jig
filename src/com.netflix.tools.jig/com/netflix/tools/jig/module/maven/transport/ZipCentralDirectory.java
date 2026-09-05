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

package com.netflix.tools.jig.module.maven.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Parses a zip central directory from suffix bytes, enabling targeted
 * extraction of individual entries via byte-offset range reads.
 *
 * <p>Designed for use with HTTP suffix range responses — only the tail
 * of the zip file is needed to enumerate all entries and determine their
 * byte offsets for targeted reads.
 */
public final class ZipCentralDirectory {

    private static final int EOCD_SIG = 0x06054b50;
    private static final int CD_SIG = 0x02014b50;

    public record Entry(String name, int method, long compressedSize,
                        long uncompressedSize, long localHeaderOffset) {}

    private final List<Entry> entries;
    private final byte[] rawBytes;
    private final long rawBytesStart;

    private ZipCentralDirectory(List<Entry> entries, byte[] rawBytes, long rawBytesStart) {
        this.entries = entries;
        this.rawBytes = rawBytes;
        this.rawBytesStart = rawBytesStart;
    }

    /**
     * Parses the central directory from suffix bytes. The suffix must
     * contain the end-of-central-directory record and the complete
     * central directory.
     *
     * @param suffix      the tail bytes of the zip file
     * @param suffixStart the absolute byte offset where these suffix bytes
     *                    begin in the original file
     * @return the parsed directory, or empty if the central directory
     *         is not fully contained in the suffix
     */
    static Optional<ZipCentralDirectory> parse(byte[] suffix, long suffixStart) {
        return parse(suffix, suffixStart, 0);
    }

    public static Optional<ZipCentralDirectory> parse(byte[] suffix, long suffixStart, int archivePrefixLength) {
        var buf = ByteBuffer.wrap(suffix).order(ByteOrder.LITTLE_ENDIAN);
        int eocdPos = findEocd(buf);
        if (eocdPos < 0) {
            return Optional.empty();
        }

        long cdAbsoluteOffset = Integer.toUnsignedLong(buf.getInt(eocdPos + 16)) + archivePrefixLength;
        long cdInSuffix = cdAbsoluteOffset - suffixStart;
        if (cdInSuffix < 0 || cdInSuffix >= suffix.length) {
            return Optional.empty();
        }

        var entries = new ArrayList<Entry>();
        int pos = (int) cdInSuffix;
        while (pos + 46 <= suffix.length) {
            if (buf.getInt(pos) != CD_SIG) {
                break;
            }
            int method = Short.toUnsignedInt(buf.getShort(pos + 10));
            long compSize = Integer.toUnsignedLong(buf.getInt(pos + 20));
            long uncompSize = Integer.toUnsignedLong(buf.getInt(pos + 24));
            int nameLen = Short.toUnsignedInt(buf.getShort(pos + 28));
            int extraLen = Short.toUnsignedInt(buf.getShort(pos + 30));
            int commentLen = Short.toUnsignedInt(buf.getShort(pos + 32));
            long localOffset = Integer.toUnsignedLong(buf.getInt(pos + 42)) + archivePrefixLength;
            String name = new String(suffix, pos + 46, nameLen);
            entries.add(new Entry(name, method, compSize, uncompSize, localOffset));
            pos += 46 + nameLen + extraLen + commentLen;
        }
        return Optional.of(new ZipCentralDirectory(entries, suffix, suffixStart));
    }

    /**
     * Reads the CD offset and size from the EOCD record in a suffix that
     * may be too small to contain the full central directory.
     *
     * @return {@code [cdOffset, cdSize]} absolute byte positions, or empty
     *         if no EOCD was found
     */
    static Optional<long[]> readEocd(byte[] suffix) {
        var buf = ByteBuffer.wrap(suffix).order(ByteOrder.LITTLE_ENDIAN);
        int eocdPos = findEocd(buf);
        if (eocdPos < 0) {
            return Optional.empty();
        }
        long cdSize = Integer.toUnsignedLong(buf.getInt(eocdPos + 12));
        long cdOffset = Integer.toUnsignedLong(buf.getInt(eocdPos + 16));
        return Optional.of(new long[] {cdOffset, cdSize});
    }

    List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> find(String name) {
        return entries.stream()
                .filter(e -> e.name().equals(name))
                .findFirst();
    }

    public Set<String> entryNames() {
        var names = new HashSet<String>();
        for (var entry : entries) {
            names.add(entry.name());
        }
        return names;
    }

    /**
     * Tries to extract an entry directly from the bytes already held by
     * this directory (the original suffix). Returns empty if the entry's
     * local file header and compressed data are not within those bytes.
     */
    Optional<byte[]> tryExtract(Entry entry) {
        long[] range = entryByteRange(entry);
        long offsetInRaw = range[0] - rawBytesStart;
        long endInRaw = range[1] - rawBytesStart;
        if (offsetInRaw < 0 || endInRaw > rawBytes.length) {
            return Optional.empty();
        }
        byte[] localData = Arrays.copyOfRange(rawBytes, (int) offsetInRaw, (int) endInRaw);
        return Optional.of(extractEntry(entry, localData));
    }

    /**
     * Extracts uncompressed entry bytes from raw data read at the entry's
     * local file header offset. The raw data must start at the local file
     * header.
     */
    public static byte[] extractEntry(Entry entry, byte[] localHeaderData) {
        var buf = ByteBuffer.wrap(localHeaderData).order(ByteOrder.LITTLE_ENDIAN);
        int nameLen = Short.toUnsignedInt(buf.getShort(26));
        int extraLen = Short.toUnsignedInt(buf.getShort(28));
        int dataStart = 30 + nameLen + extraLen;
        int compSize = (int) entry.compressedSize();

        if (entry.method() == 0) {
            byte[] result = new byte[compSize];
            System.arraycopy(localHeaderData, dataStart, result, 0, compSize);
            return result;
        }

        try {
            var inflater = new Inflater(true);
            inflater.setInput(localHeaderData, dataStart, compSize);
            byte[] result = new byte[(int) entry.uncompressedSize()];
            inflater.inflate(result);
            inflater.end();
            return result;
        } catch (DataFormatException e) {
            throw new IllegalStateException("Failed to inflate " + entry.name(), e);
        }
    }

    /**
     * Computes the byte range needed to read an entry's local file header
     * and compressed data. Returns {@code [start, end)} offsets in the
     * original file.
     */
    static long[] entryByteRange(Entry entry) {
        long start = entry.localHeaderOffset();
        long end = start
                + 30
                + entry.name().length()
                + 256
                + entry.compressedSize();
        return new long[] {start, end};
    }

    private static int findEocd(ByteBuffer buf) {
        for (int i = buf.capacity() - 22; i >= 0; i--) {
            if (buf.getInt(i) == EOCD_SIG) {
                return i;
            }
        }
        return -1;
    }
}
