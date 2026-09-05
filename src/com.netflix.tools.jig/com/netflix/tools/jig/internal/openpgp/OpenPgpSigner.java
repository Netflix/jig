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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;

import com.netflix.tools.jig.internal.openpgp.SecretKeys.SigningKey;

/**
 * Implements the RFC 9580 sections 5.2.3 and 5.2.4 subset needed for detached
 * signatures.
 */
public final class OpenPgpSigner {
    private static final int BINARY_DOCUMENT = 0;
    private static final int SHA512 = 10;

    private final SigningKey key;
    private final SecureRandom random;

    private OpenPgpSigner(SigningKey key) {
        this.key = key;
        this.random = new SecureRandom();
    }

    public static OpenPgpSigner load(byte[] transferableSecretKey, String fingerprint, char[] passphrase) {
        return new OpenPgpSigner(SecretKeys.load(transferableSecretKey, fingerprint, passphrase));
    }

    public byte[] fingerprint() {
        return key.fingerprint().clone();
    }

    public PublicKey publicKey() {
        return key.publicKey();
    }

    public byte[] sign(byte[] document, Instant creationTime) {
        try {
            return sign(new ByteArrayInputStream(document), creationTime);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public byte[] sign(InputStream document, Instant creationTime) throws IOException {
        long seconds = creationTime.getEpochSecond();
        if (seconds < 0 || seconds > 0xffff_ffffL) {
            throw new IllegalArgumentException("OpenPGP signature time is out of range");
        }

        byte[] salt = new byte[key.version() == 6 ? 32 : 0];
        random.nextBytes(salt);
        byte[] hashedSubpackets = concat(creationTime(seconds), issuerFingerprint());
        var signedHeader = new ByteArrayOutputStream(8 + hashedSubpackets.length);
        signedHeader.write(key.version());
        signedHeader.write(BINARY_DOCUMENT);
        signedHeader.write(key.algorithm());
        signedHeader.write(SHA512);
        if (key.version() == 4) {
            writeU16(signedHeader, hashedSubpackets.length);
        } else {
            writeU32(signedHeader, hashedSubpackets.length);
        }
        signedHeader.writeBytes(hashedSubpackets);
        byte[] header = signedHeader.toByteArray();
        byte[] trailer = {
            (byte) key.version(),
            (byte) 0xff,
            (byte) (header.length >>> 24),
            (byte) (header.length >>> 16),
            (byte) (header.length >>> 8),
            (byte) header.length
        };

        byte[] digest;
        byte[] cryptographicSignature;
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            Signature rsa = isRsa() ? Signature.getInstance("SHA512withRSA") : null;
            if (rsa != null) {
                rsa.initSign(key.privateKey());
            }
            update(sha512, rsa, salt);
            byte[] buffer = new byte[8192];
            for (int count; (count = document.read(buffer)) >= 0; ) {
                update(sha512, rsa, buffer, count);
            }
            update(sha512, rsa, header);
            update(sha512, rsa, trailer);
            digest = sha512.digest();
            if (rsa != null) {
                cryptographicSignature = rsa.sign();
            } else {
                Signature ed25519 = Signature.getInstance("Ed25519");
                ed25519.initSign(key.privateKey());
                ed25519.update(digest);
                cryptographicSignature = ed25519.sign();
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not create OpenPGP signature", e);
        }

        byte[] unhashedSubpackets = issuerKeyId();
        var body = new ByteArrayOutputStream(header.length + unhashedSubpackets.length + cryptographicSignature.length + salt.length + 12);
        body.writeBytes(header);
        if (key.version() == 4) {
            writeU16(body, unhashedSubpackets.length);
        } else {
            writeU32(body, unhashedSubpackets.length);
        }
        body.writeBytes(unhashedSubpackets);
        body.write(digest[0]);
        body.write(digest[1]);
        if (key.version() == 6) {
            body.write(salt.length);
            body.writeBytes(salt);
        }
        writeSignatureValue(body, cryptographicSignature);
        Arrays.fill(digest, (byte) 0);
        Arrays.fill(cryptographicSignature, (byte) 0);
        Arrays.fill(salt, (byte) 0);
        return Armor.encodeSignature(Packets.encode(2, body.toByteArray()));
    }

    private boolean isRsa() {
        return key.algorithm() == 1 || key.algorithm() == 3;
    }

    private void writeSignatureValue(ByteArrayOutputStream output, byte[] signature) {
        switch (key.algorithm()) {
            case 1, 3 -> writeMpi(output, signature);
            case 22 -> {
                if (signature.length != 64) {
                    throw new IllegalArgumentException("Ed25519 signature is not 64 octets");
                }
                writeMpi(output, Arrays.copyOfRange(signature, 0, 32));
                writeMpi(output, Arrays.copyOfRange(signature, 32, 64));
            }
            case 27 -> {
                if (signature.length != 64) {
                    throw new IllegalArgumentException("Ed25519 signature is not 64 octets");
                }
                output.writeBytes(signature);
            }
            default -> throw new IllegalStateException("Unsupported signing algorithm " + key.algorithm());
        }
    }

    private byte[] creationTime(long seconds) {
        return new byte[] {5, 2, (byte) (seconds >>> 24), (byte) (seconds >>> 16),
                (byte) (seconds >>> 8), (byte) seconds};
    }

    private byte[] issuerFingerprint() {
        byte[] fingerprint = key.fingerprint();
        var result = new byte[fingerprint.length + 3];
        result[0] = (byte) (fingerprint.length + 2);
        result[1] = 33;
        result[2] = (byte) key.version();
        System.arraycopy(fingerprint, 0, result, 3, fingerprint.length);
        return result;
    }

    private byte[] issuerKeyId() {
        byte[] fingerprint = key.fingerprint();
        var result = new byte[10];
        result[0] = 9;
        result[1] = 16;
        int fingerprintOffset = key.version() == 4 ? fingerprint.length - 8 : 0;
        System.arraycopy(fingerprint, fingerprintOffset, result, 2, 8);
        return result;
    }

    private static void update(MessageDigest digest, Signature signature, byte[] value) throws GeneralSecurityException {
        update(digest, signature, value, value.length);
    }

    private static void update(MessageDigest digest, Signature signature, byte[] value,
            int length)
            throws GeneralSecurityException {
        digest.update(value, 0, length);
        if (signature != null) {
            signature.update(value, 0, length);
        }
    }

    private static void writeMpi(ByteArrayOutputStream output, byte[] unsigned) {
        int offset = 0;
        while (offset < unsigned.length - 1 && unsigned[offset] == 0) {
            offset++;
        }
        int first = unsigned[offset] & 0xff;
        int bits = (unsigned.length - offset - 1) * 8 + (32 - Integer.numberOfLeadingZeros(first));
        writeU16(output, bits);
        output.write(unsigned, offset, unsigned.length - offset);
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException("OpenPGP value exceeds two-byte length");
        }
        output.write(value >>> 8);
        output.write(value);
    }

    private static void writeU32(ByteArrayOutputStream output, int value) {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
