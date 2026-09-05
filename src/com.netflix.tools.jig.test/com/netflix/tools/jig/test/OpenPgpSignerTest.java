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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import com.netflix.tools.jig.internal.openpgp.OpenPgpSigner;
import com.netflix.tools.jig.test.OpenPgpTestKeys.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPgpSignerTest {
    private static final Material TEST_KEY = OpenPgpTestKeys.encryptedRsa();
    private static final String RSA_SIGNING_KEY = TEST_KEY.fingerprint();
    private static final Instant SIGNATURE_TIME = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void signsWithAnEncryptedRsaKeyFromAnArmoredTransferableSecretKey() throws Exception {
        byte[] document = "publication artifact\n".getBytes(StandardCharsets.UTF_8);
        var signer = OpenPgpSigner.load(testKey(), RSA_SIGNING_KEY, "TEST".toCharArray());

        byte[] armored = signer.sign(document, SIGNATURE_TIME);

        assertTrue(new String(armored, StandardCharsets.US_ASCII).startsWith("-----BEGIN PGP SIGNATURE-----\n\n"));
        assertArrayEquals(hex(RSA_SIGNING_KEY), signer.fingerprint());
        verify(document, armored, (RSAPublicKey) signer.publicKey());
    }

    @Test
    void selectsTheFirstSupportedRsaKeyWhenNoFingerprintIsProvided() throws Exception {
        var signer = OpenPgpSigner.load(testKey(), null, "TEST".toCharArray());

        assertArrayEquals(hex(RSA_SIGNING_KEY), signer.fingerprint());
    }

    @Test
    void rejectsAnIncorrectPassphrase() throws Exception {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> OpenPgpSigner.load(testKey(), RSA_SIGNING_KEY, "wrong".toCharArray()));

        assertTrue(exception.getMessage().contains("passphrase"),
                exception.getMessage());
    }

    @Test
    void requiresAFullFingerprintWhenSelectingAKey() throws Exception {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> OpenPgpSigner.load(testKey(), "E9AF6EFB", "TEST".toCharArray()));

        assertEquals("OpenPGP fingerprint must contain 40 or 64 hexadecimal characters", exception.getMessage());
    }

    private static byte[] testKey() {
        return TEST_KEY.armored();
    }

    private static void verify(byte[] document, byte[] armored, RSAPublicKey publicKey) throws Exception {
        byte[] packet = decodeArmor(armored);
        int[] header = packetHeader(packet);
        assertEquals(2, header[0]);
        byte[] body = Arrays.copyOfRange(packet, header[1], header[1] + header[2]);
        assertEquals(4, body[0] & 0xff);
        assertEquals(0, body[1] & 0xff);
        assertEquals(1, body[2] & 0xff);
        assertEquals(10, body[3] & 0xff);

        int hashedLength = unsignedShort(body, 4);
        int signedHeaderLength = 6 + hashedLength;
        int unhashedLength = unsignedShort(body, signedHeaderLength);
        int digestPrefix = signedHeaderLength + 2 + unhashedLength;
        int mpi = digestPrefix + 2;
        int signatureBits = unsignedShort(body, mpi);
        int signatureLength = (signatureBits + 7) / 8;
        byte[] encodedSignature = Arrays.copyOfRange(body, mpi + 2, mpi + 2 + signatureLength);
        byte[] fixedSignature = new byte[(publicKey.getModulus().bitLength() + 7) / 8];
        System.arraycopy(encodedSignature, 0, fixedSignature, fixedSignature.length - encodedSignature.length, encodedSignature.length);

        var verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(publicKey);
        verifier.update(document);
        verifier.update(body, 0, signedHeaderLength);
        verifier.update(
                new byte[] {4, (byte) 0xff, (byte) (signedHeaderLength >>> 24),
                        (byte) (signedHeaderLength >>> 16), (byte) (signedHeaderLength >>> 8), (byte) signedHeaderLength});
        assertTrue(verifier.verify(fixedSignature));
    }

    private static byte[] decodeArmor(byte[] armored) throws Exception {
        String text = new String(armored, StandardCharsets.US_ASCII);
        int start = text.indexOf("\n\n") + 2;
        int end = text.indexOf("\n=", start);
        String payload = text.substring(start, end).replace("\n", "");
        byte[] packet = Base64.getDecoder().decode(payload);
        String checksum = text.substring(end + 2, text.indexOf('\n', end + 2));
        int expected = new BigInteger(1, Base64.getDecoder()
                .decode(checksum)).intValue();
        assertEquals(expected, crc24(packet));
        return packet;
    }

    private static int[] packetHeader(byte[] packet) {
        int first = packet[0] & 0xff;
        assertEquals(0xc0, first & 0xc0);
        int length = packet[1] & 0xff;
        if (length < 192) {
            return new int[] {first & 0x3f, 2, length};
        }
        if (length <= 223) {
            return new int[] {first & 0x3f, 3, ((length - 192) << 8) + (packet[2] & 0xff) + 192};
        }
        assertEquals(255, length);
        return new int[] {first & 0x3f, 6, ((packet[2] & 0xff) << 24)
                | ((packet[3] & 0xff) << 16)
                | ((packet[4] & 0xff) << 8)
                | (packet[5] & 0xff)
        };
    }

    private static int unsignedShort(byte[] value, int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
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
