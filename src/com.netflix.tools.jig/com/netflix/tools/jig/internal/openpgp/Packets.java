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

package com.netflix.tools.jig.internal.openpgp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads and writes the OpenPGP packet framing described by RFC 9580 section
 * 4.2.
 */
final class Packets {
    record Packet(int tag, byte[] body) {}

    private Packets() {}

    static List<Packet> read(byte[] encoded) {
        var packets = new ArrayList<Packet>();
        var input = new Cursor(encoded);
        while (input.remaining() > 0) {
            int first = input.u8();
            if ((first & 0x80) == 0) {
                throw new IllegalArgumentException("Malformed OpenPGP packet header");
            }
            int tag;
            int length;
            if ((first & 0x40) != 0) {
                tag = first & 0x3f;
                length = newLength(input);
            } else {
                tag = (first >>> 2) & 0x0f;
                length = oldLength(input, first & 0x03);
            }
            if (length < 0 || length > input.remaining()) {
                throw new IllegalArgumentException("OpenPGP packet extends beyond key material");
            }
            packets.add(new Packet(tag, input.bytes(length)));
        }
        return List.copyOf(packets);
    }

    static byte[] encode(int tag, byte[] body) {
        if (tag < 0 || tag > 63) {
            throw new IllegalArgumentException("Invalid OpenPGP packet tag");
        }
        var output = new ByteArrayOutputStream(body.length + 6);
        output.write(0xc0 | tag);
        writeLength(output, body.length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static int newLength(Cursor input) {
        int first = input.u8();
        if (first < 192) {
            return first;
        }
        if (first <= 223) {
            return ((first - 192) << 8) + input.u8() + 192;
        }
        if (first == 255) {
            return input.i32();
        }
        throw new IllegalArgumentException("Partial OpenPGP packet lengths are not supported");
    }

    private static int oldLength(Cursor input, int type) {
        return switch (type) {
            case 0 -> input.u8();
            case 1 -> input.u16();
            case 2 -> input.i32();
            default -> throw new IllegalArgumentException("Indeterminate OpenPGP packet lengths are not supported");
        };
    }

    private static void writeLength(ByteArrayOutputStream output, int length) {
        if (length < 192) {
            output.write(length);
        } else if (length <= 8383) {
            int value = length - 192;
            output.write((value >>> 8) + 192);
            output.write(value);
        } else {
            output.write(255);
            output.write(length >>> 24);
            output.write(length >>> 16);
            output.write(length >>> 8);
            output.write(length);
        }
    }

    static final class Cursor {
        private final byte[] value;
        private int offset;

        Cursor(byte[] value) {
            this.value = value;
        }

        int position() {
            return offset;
        }

        int remaining() {
            return value.length - offset;
        }

        int u8() {
            require(1);
            return value[offset++] & 0xff;
        }

        int u16() {
            return (u8() << 8) | u8();
        }

        int i32() {
            return (u8() << 24)
                    | (u8() << 16)
                    | (u8() << 8)
                    | u8();
        }

        byte[] bytes(int length) {
            require(length);
            byte[] result = Arrays.copyOfRange(value, offset, offset + length);
            offset += length;
            return result;
        }

        byte[] remainingBytes() {
            return bytes(remaining());
        }

        private void require(int length) {
            if (length < 0 || length > remaining()) {
                throw new IllegalArgumentException("Truncated OpenPGP data");
            }
        }
    }
}
