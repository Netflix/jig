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

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Encodes and decodes the ASCII armor described by RFC 9580 section 6.2. */
final class Armor {
    private static final String PRIVATE_KEY = "PGP PRIVATE KEY BLOCK";
    private static final String SIGNATURE = "PGP SIGNATURE";

    private Armor() {}

    static byte[] decodePrivateKey(byte[] input) {
        if (input.length > 1024 * 1024) {
            throw new IllegalArgumentException("OpenPGP key material exceeds 1 MiB");
        }
        String text = new String(input, StandardCharsets.US_ASCII).replace("\r\n", "\n").replace('\r', '\n');
        String begin = "-----BEGIN " + PRIVATE_KEY + "-----";
        String end = "-----END " + PRIVATE_KEY + "-----";
        String[] lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length && lines[index].isBlank()) {
            index++;
        }
        if (index >= lines.length || !lines[index++].equals(begin)) {
            throw new IllegalArgumentException("MAVEN_GPG_KEY is not an armored OpenPGP transferable secret key");
        }
        while (index < lines.length && !lines[index].isEmpty()) {
            if (!lines[index].contains(":")) {
                throw new IllegalArgumentException("Malformed OpenPGP armor header");
            }
            index++;
        }
        if (index >= lines.length) {
            throw new IllegalArgumentException("Malformed OpenPGP armor");
        }
        index++;

        var payload = new StringBuilder();
        String checksum = null;
        boolean ended = false;
        for (; index < lines.length; index++) {
            String line = lines[index];
            if (line.equals(end)) {
                ended = true;
                break;
            }
            if (line.startsWith("=")) {
                checksum = line.substring(1);
            } else if (!line.isBlank()) {
                if (checksum != null) {
                    throw new IllegalArgumentException("Malformed OpenPGP armor checksum");
                }
                payload.append(line.trim());
            }
        }
        if (!ended) {
            throw new IllegalArgumentException("OpenPGP armor has no end marker");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(payload.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed base64 in OpenPGP armor", e);
        }
        if (checksum != null) {
            byte[] expected;
            try {
                expected = Base64.getDecoder().decode(checksum);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Malformed OpenPGP armor checksum", e);
            }
            if (expected.length != 3 || crc24(decoded) != unsigned24(expected)) {
                throw new IllegalArgumentException("OpenPGP armor checksum does not match");
            }
        }
        return decoded;
    }

    static byte[] encodeSignature(byte[] packet) {
        String payload = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(packet);
        int crc = crc24(packet);
        byte[] checksum = {(byte) (crc >>> 16), (byte) (crc >>> 8), (byte) crc};
        String result = "-----BEGIN "
                + SIGNATURE
                + "-----\n\n"
                + payload
                + "\n="
                + Base64.getEncoder().encodeToString(checksum)
                + "\n"
                + "-----END "
                + SIGNATURE
                + "-----\n";
        return result.getBytes(StandardCharsets.US_ASCII);
    }

    private static int unsigned24(byte[] value) {
        return ((value[0] & 0xff) << 16) | ((value[1] & 0xff) << 8) | (value[2] & 0xff);
    }

    private static int crc24(byte[] value) {
        int crc = 0xb704ce;
        for (byte b : value) {
            crc ^= (b & 0xff) << 16;
            for (int i = 0; i < 8; i++) {
                crc <<= 1;
                if ((crc & 0x1000000) != 0) {
                    crc ^= 0x1864cfb;
                }
            }
        }
        return crc & 0xffffff;
    }
}
