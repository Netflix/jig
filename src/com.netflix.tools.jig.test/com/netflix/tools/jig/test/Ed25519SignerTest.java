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

package com.netflix.tools.jig.test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import com.netflix.tools.jig.internal.openpgp.OpenPgpSigner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ed25519SignerTest {
    // RFC 8032, section 7.1, test vector 1.
    private static final byte[] SEED = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final byte[] PUBLIC = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] ED25519_LEGACY_OID = hex("2b06010401da470f01");
    private static final Instant TIME = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void signsWithAV4Ed25519LegacyKey() throws Exception {
        var signer = OpenPgpSigner.load(v4Key(), null, null);
        byte[] document = "legacy Ed25519\n".getBytes(StandardCharsets.UTF_8);

        byte[] signature = signer.sign(document, TIME);

        verify(document, signature, 4, 22);
    }

    @Test
    void signsWithAV6NativeEd25519Key() throws Exception {
        var signer = OpenPgpSigner.load(v6Key(), null, null);
        byte[] document = "native Ed25519\n".getBytes(StandardCharsets.UTF_8);

        byte[] signature = signer.sign(document, TIME);

        assertEquals(32, signer.fingerprint().length);
        verify(document, signature, 6, 27);
    }

    private static byte[] v4Key() throws Exception {
        var publicBody = new ByteArrayOutputStream();
        publicBody.write(4);
        writeU32(publicBody, TIME.getEpochSecond());
        publicBody.write(22);
        publicBody.write(ED25519_LEGACY_OID.length);
        publicBody.writeBytes(ED25519_LEGACY_OID);
        writeMpi(publicBody, concat(new byte[] {0x40}, PUBLIC));

        var secret = new ByteArrayOutputStream();
        writeMpi(secret, SEED);
        byte[] secretBody = secret.toByteArray();
        var body = new ByteArrayOutputStream();
        body.writeBytes(publicBody.toByteArray());
        body.write(0);
        body.writeBytes(secretBody);
        writeU16(body, checksum(secretBody));
        return armor(packet(5, body.toByteArray()));
    }

    private static byte[] v6Key() throws Exception {
        var body = new ByteArrayOutputStream();
        body.write(6);
        writeU32(body, TIME.getEpochSecond());
        body.write(27);
        writeU32(body, PUBLIC.length);
        body.writeBytes(PUBLIC);
        body.write(0);
        body.writeBytes(SEED);
        return armor(packet(5, body.toByteArray()));
    }

    private static void verify(byte[] document, byte[] armored, int version,
            int algorithm)
            throws Exception {
        byte[] packet = decodeArmor(armored);
        int[] packetHeader = packetHeader(packet);
        assertEquals(2, packetHeader[0]);
        byte[] body = Arrays.copyOfRange(packet, packetHeader[1], packetHeader[1] + packetHeader[2]);
        assertEquals(version, body[0] & 0xff);
        assertEquals(algorithm, body[2] & 0xff);
        assertEquals(10, body[3] & 0xff);

        int hashedLength = version == 4 ? unsignedShort(body, 4) : integer(body, 4);
        int signedHeaderLength = (version == 4 ? 6 : 8) + hashedLength;
        int unhashedLength = version == 4 ? unsignedShort(body, signedHeaderLength) : integer(body, signedHeaderLength);
        int signatureOffset = signedHeaderLength
                + (version == 4 ? 2 : 4)
                + unhashedLength
                + 2;
        byte[] salt;
        byte[] encodedSignature;
        if (version == 6) {
            int saltLength = body[signatureOffset++] & 0xff;
            salt = Arrays.copyOfRange(body, signatureOffset, signatureOffset + saltLength);
            signatureOffset += saltLength;
            encodedSignature = Arrays.copyOfRange(body, signatureOffset, signatureOffset + 64);
        } else {
            salt = new byte[0];
            int[] first = mpi(body, signatureOffset);
            int[] second = mpi(body, first[1]);
            encodedSignature = concat(padded(body, first, 32), padded(body, second, 32));
        }

        MessageDigest hash = MessageDigest.getInstance("SHA-512");
        hash.update(salt);
        hash.update(document);
        hash.update(body, 0, signedHeaderLength);
        hash.update(
                new byte[] {
                    (byte) version,
                    (byte) 0xff,
                    (byte) (signedHeaderLength >>> 24),
                    (byte) (signedHeaderLength >>> 16),
                    (byte) (signedHeaderLength >>> 8),
                    (byte) signedHeaderLength
                });
        byte[] digest = hash.digest();

        byte[] encodedPoint = PUBLIC.clone();
        boolean xOdd = (encodedPoint[31] & 0x80) != 0;
        encodedPoint[31] &= 0x7f;
        reverse(encodedPoint);
        var publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, encodedPoint))));
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(digest);
        assertTrue(verifier.verify(encodedSignature));
    }

    private static int[] mpi(byte[] value, int offset) {
        int length = (unsignedShort(value, offset) + 7) / 8;
        return new int[] {offset + 2, offset + 2 + length};
    }

    private static byte[] padded(byte[] value, int[] range, int length) {
        byte[] result = new byte[length];
        System.arraycopy(value, range[0], result, length - (range[1] - range[0]),
                range[1] - range[0]);
        return result;
    }

    private static byte[] packet(int tag, byte[] body) {
        var result = new ByteArrayOutputStream();
        result.write(0xc0 | tag);
        if (body.length < 192) {
            result.write(body.length);
        } else {
            result.write(255);
            writeU32(result, body.length);
        }
        result.writeBytes(body);
        return result.toByteArray();
    }

    private static byte[] armor(byte[] packet) {
        int crc = crc24(packet);
        byte[] check = {(byte) (crc >>> 16), (byte) (crc >>> 8), (byte) crc};
        String payload = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(packet);
        return ("-----BEGIN PGP PRIVATE KEY BLOCK-----\n\n" + payload + "\n=" + Base64.getEncoder().encodeToString(check) + "\n-----END PGP PRIVATE KEY BLOCK-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] decodeArmor(byte[] armored) {
        String text = new String(armored, StandardCharsets.US_ASCII);
        int start = text.indexOf("\n\n") + 2;
        int end = text.indexOf("\n=", start);
        return Base64.getDecoder().decode(text.substring(start, end)
                .replace("\n", ""));
    }

    private static int[] packetHeader(byte[] packet) {
        int first = packet[0] & 0xff;
        int length = packet[1] & 0xff;
        if (length < 192) {
            return new int[] {first & 0x3f, 2, length};
        }
        return new int[] {first & 0x3f, 6, integer(packet, 2)};
    }

    private static int unsignedShort(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private static int integer(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void writeMpi(ByteArrayOutputStream output, byte[] value) {
        int first = value[0] & 0xff;
        int bits = (value.length - 1) * 8 + 32 - Integer.numberOfLeadingZeros(first);
        writeU16(output, bits);
        output.writeBytes(value);
    }

    private static void writeU16(ByteArrayOutputStream output, long value) {
        output.write((int) (value >>> 8));
        output.write((int) value);
    }

    private static void writeU32(ByteArrayOutputStream output, long value) {
        output.write((int) (value >>> 24));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 8));
        output.write((int) value);
    }

    private static int checksum(byte[] value) {
        int result = 0;
        for (byte b : value) {
            result = (result + (b & 0xff)) & 0xffff;
        }
        return result;
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

    private static void reverse(byte[] value) {
        for (int left = 0, right = value.length - 1;
             left < right;
             left++, right--) {
            byte swap = value[left];
            value[left] = value[right];
            value[right] = swap;
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
