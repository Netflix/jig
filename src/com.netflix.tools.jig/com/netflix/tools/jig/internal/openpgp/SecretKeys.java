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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static com.netflix.tools.jig.internal.openpgp.Packets.Cursor;
import static com.netflix.tools.jig.internal.openpgp.Packets.Packet;

/** Parses the RFC 9580 sections 3.7 and 5.5 subset used by the signer. */
final class SecretKeys {
    private static final byte[] ED25519_LEGACY_OID = HexFormat.of().parseHex("2b06010401da470f01");

    record SigningKey(int version, int algorithm, byte[] fingerprint,
                      PublicKey publicKey, PrivateKey privateKey) {}

    private record Candidate(
            int version,
            int algorithm,
            byte[] fingerprint,
            BigInteger modulus,
            BigInteger publicExponent,
            byte[] encodedPublicKey,
            byte[] body,
            int secretOffset) {}

    private record S2k(int type, String digest, byte[] salt,
                       long count) {}

    private SecretKeys() {}

    static SigningKey load(byte[] armored, String selectedFingerprint, char[] passphrase) {
        byte[] expected = fingerprint(selectedFingerprint);
        byte[] encoded = Armor.decodePrivateKey(armored);
        try {
            Candidate selected = null;
            for (Packet packet : Packets.read(encoded)) {
                if (packet.tag() != 5 && packet.tag() != 7) {
                    continue;
                }
                Candidate candidate = candidate(packet.body());
                if (candidate == null) {
                    continue;
                }
                if (expected != null && !MessageDigest.isEqual(expected, candidate.fingerprint())) {
                    continue;
                }
                selected = candidate;
                break;
            }
            if (selected == null) {
                throw new IllegalArgumentException(expected == null ? "Transferable secret key contains no supported signing key" : "Transferable secret key does not contain the requested signing key");
            }
            return unlock(selected, passphrase);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (expected != null) {
                Arrays.fill(expected, (byte) 0);
            }
        }
    }

    private static Candidate candidate(byte[] body) {
        var input = new Cursor(body);
        int version = input.u8();
        if (version != 4 && version != 6) {
            return null;
        }
        input.i32();
        int algorithm = input.u8();
        BigInteger modulus = null;
        BigInteger publicExponent = null;
        byte[] encodedPublicKey = null;
        if (version == 6) {
            int materialLength = input.i32();
            byte[] material = input.bytes(materialLength);
            if (algorithm != 27 || material.length != 32) {
                return null;
            }
            encodedPublicKey = material;
        } else if (algorithm == 1 || algorithm == 3) {
            modulus = mpi(input);
            publicExponent = mpi(input);
        } else if (algorithm == 22) {
            byte[] oid = input.bytes(input.u8());
            if (!Arrays.equals(oid, ED25519_LEGACY_OID)) {
                return null;
            }
            byte[] point = mpiBytes(input);
            if (point.length != 33 || point[0] != 0x40) {
                return null;
            }
            encodedPublicKey = Arrays.copyOfRange(point, 1, point.length);
        } else {
            return null;
        }
        int publicLength = input.position();
        byte[] publicBody = Arrays.copyOf(body, publicLength);
        byte[] fingerprint = version == 4 ? digest(
                "SHA-1",
                concat(new byte[] {(byte) 0x99, (byte) (publicLength >>> 8), (byte) publicLength},
                        publicBody))
                : digest(
                "SHA-256",
                concat(
                        new byte[] {(byte) 0x9b, (byte) (publicLength >>> 24), (byte) (publicLength >>> 16),
                                (byte) (publicLength >>> 8), (byte) publicLength},
                        publicBody));
        Arrays.fill(publicBody, (byte) 0);
        return new Candidate(version, algorithm, fingerprint, modulus, publicExponent, encodedPublicKey,
                body, publicLength);
    }

    private static SigningKey unlock(Candidate candidate, char[] passphrase) {
        byte[] protectedSecret = Arrays.copyOfRange(candidate.body(), candidate.secretOffset(), candidate.body().length);
        byte[] clear = null;
        try {
            var input = new Cursor(protectedSecret);
            int usage = input.u8();
            if (usage == 0) {
                clear = input.remainingBytes();
            } else if (candidate.version() == 6) {
                throw new IllegalArgumentException("Protected OpenPGP v6 secret keys are not yet supported");
            } else if (usage == 254 || usage == 255) {
                int symmetricAlgorithm = input.u8();
                S2k s2k = readS2k(input);
                int keyLength = aesKeyLength(symmetricAlgorithm);
                byte[] iv = input.bytes(16);
                byte[] encrypted = input.remainingBytes();
                byte[] password = utf8(passphrase);
                byte[] key = deriveKey(s2k, password, keyLength);
                try {
                    var cipher = Cipher.getInstance("AES/CFB/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    clear = cipher.doFinal(encrypted);
                } catch (GeneralSecurityException e) {
                    throw new IllegalArgumentException("Could not decrypt OpenPGP secret key", e);
                } finally {
                    Arrays.fill(password, (byte) 0);
                    Arrays.fill(key, (byte) 0);
                    Arrays.fill(iv, (byte) 0);
                    Arrays.fill(encrypted, (byte) 0);
                }
            } else {
                throw new IllegalArgumentException("Unsupported OpenPGP secret-key protection mode " + usage);
            }

            byte[] secret = verifyIntegrity(clear, usage, candidate.version());
            try {
                return candidate.algorithm() == 1 || candidate.algorithm() == 3
                        ? rsaKey(candidate, secret)
                        : ed25519Key(candidate, secret);
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        } catch (IllegalArgumentException e) {
            if (clear != null && (e.getMessage() == null || !e.getMessage().contains("passphrase"))) {
                throw new IllegalArgumentException("OpenPGP key passphrase is incorrect or secret key is corrupt", e);
            }
            throw e;
        } finally {
            Arrays.fill(protectedSecret, (byte) 0);
            if (clear != null) {
                Arrays.fill(clear, (byte) 0);
            }
        }
    }

    private static SigningKey rsaKey(Candidate candidate, byte[] secretBytes) {
        try {
            var secret = new Cursor(secretBytes);
            BigInteger privateExponent = mpi(secret);
            BigInteger primeP = mpi(secret);
            BigInteger primeQ = mpi(secret);
            mpi(secret); // OpenPGP u = p^-1 mod q; JCA derives the opposite coefficient below.
            requireConsumed(secret);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = factory.generatePublic(new RSAPublicKeySpec(candidate.modulus(), candidate.publicExponent()));
            PrivateKey privateKey = factory.generatePrivate(
                    new RSAPrivateCrtKeySpec(
                            candidate.modulus(),
                            candidate.publicExponent(),
                            privateExponent,
                            primeP,
                            primeQ,
                            privateExponent.mod(primeP.subtract(BigInteger.ONE)),
                            privateExponent.mod(primeQ.subtract(BigInteger.ONE)),
                            primeQ.modInverse(primeP)));
            return signingKey(candidate, publicKey, privateKey);
        } catch (GeneralSecurityException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid RSA material in OpenPGP secret key", e);
        }
    }

    private static SigningKey ed25519Key(Candidate candidate, byte[] secretBytes) {
        try {
            byte[] seed;
            if (candidate.version() == 4) {
                var secret = new Cursor(secretBytes);
                seed = leftPad(mpiBytes(secret), 32);
                requireConsumed(secret);
            } else {
                if (secretBytes.length != 32) {
                    throw new IllegalArgumentException("OpenPGP v6 Ed25519 secret key is not 32 octets");
                }
                seed = secretBytes.clone();
            }
            byte[] encodedPoint = candidate.encodedPublicKey().clone();
            boolean xOdd = (encodedPoint[31] & 0x80) != 0;
            encodedPoint[31] &= 0x7f;
            reverse(encodedPoint);
            KeyFactory factory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = factory.generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            PublicKey publicKey = factory.generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, new BigInteger(1, encodedPoint))));
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(encodedPoint, (byte) 0);
            return signingKey(candidate, publicKey, privateKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid Ed25519 material in OpenPGP secret key", e);
        }
    }

    private static SigningKey signingKey(Candidate candidate, PublicKey publicKey, PrivateKey privateKey) {
        return new SigningKey(
                candidate.version(),
                candidate.algorithm(),
                candidate.fingerprint().clone(),
                publicKey,
                privateKey);
    }

    private static byte[] verifyIntegrity(byte[] clear, int usage, int version) {
        int checksumLength = usage == 254
                ? 20
                : version == 4 ? 2 : 0;
        if (clear.length < checksumLength) {
            throw new IllegalArgumentException("Truncated OpenPGP secret key");
        }
        byte[] secret = Arrays.copyOf(clear, clear.length - checksumLength);
        if (usage == 254) {
            byte[] expected = Arrays.copyOfRange(clear, secret.length, clear.length);
            byte[] actual = digest("SHA-1", secret);
            boolean matches = MessageDigest.isEqual(expected, actual);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(actual, (byte) 0);
            if (!matches) {
                throw new IllegalArgumentException("OpenPGP key passphrase is incorrect or secret key is corrupt");
            }
        } else if (checksumLength == 2) {
            int expected = ((clear[secret.length] & 0xff) << 8) | (clear[secret.length + 1] & 0xff);
            int actual = 0;
            for (byte value : secret) {
                actual = (actual + (value & 0xff)) & 0xffff;
            }
            if (actual != expected) {
                throw new IllegalArgumentException("OpenPGP key passphrase is incorrect or secret key is corrupt");
            }
        }
        return secret;
    }

    private static S2k readS2k(Cursor input) {
        int type = input.u8();
        String digest = s2kDigest(input.u8());
        return switch (type) {
            case 0 -> new S2k(type, digest, new byte[0], 0);
            case 1 -> new S2k(type, digest, input.bytes(8), 0);
            case 3 -> {
                byte[] salt = input.bytes(8);
                int coded = input.u8();
                long count = (16L + (coded & 15)) << ((coded >>> 4) + 6);
                yield new S2k(type, digest, salt, count);
            }
            default -> throw new IllegalArgumentException("Unsupported OpenPGP S2K type " + type);
        };
    }

    private static byte[] deriveKey(S2k s2k, byte[] password, int length) {
        byte[] material = s2k.type() == 0 ? password : concat(s2k.salt(), password);
        var result = new byte[length];
        int offset = 0;
        for (int round = 0; offset < length; round++) {
            MessageDigest digest = messageDigest(s2k.digest());
            for (int i = 0; i < round; i++) {
                digest.update((byte) 0);
            }
            if (s2k.type() == 3) {
                long remaining = Math.max(s2k.count(), material.length);
                while (remaining > 0) {
                    int count = (int) Math.min(remaining, material.length);
                    digest.update(material, 0, count);
                    remaining -= count;
                }
            } else {
                digest.update(material);
            }
            byte[] block = digest.digest();
            int count = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, result, offset, count);
            offset += count;
            Arrays.fill(block, (byte) 0);
        }
        if (material != password) {
            Arrays.fill(material, (byte) 0);
        }
        return result;
    }

    private static int aesKeyLength(int algorithm) {
        return switch (algorithm) {
            case 7 -> 16;
            case 8 -> 24;
            case 9 -> 32;
            default -> throw new IllegalArgumentException("Unsupported OpenPGP symmetric algorithm " + algorithm);
        };
    }

    private static String s2kDigest(int algorithm) {
        return switch (algorithm) {
            case 2 -> "SHA-1";
            case 8 -> "SHA-256";
            case 9 -> "SHA-384";
            case 10 -> "SHA-512";
            default -> throw new IllegalArgumentException("Unsupported OpenPGP S2K hash algorithm " + algorithm);
        };
    }

    private static BigInteger mpi(Cursor input) {
        byte[] magnitude = mpiBytes(input);
        try {
            return new BigInteger(1, magnitude);
        } finally {
            Arrays.fill(magnitude, (byte) 0);
        }
    }

    private static byte[] mpiBytes(Cursor input) {
        int bits = input.u16();
        return input.bytes((bits + 7) / 8);
    }

    private static byte[] fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(" ", "").trim();
        if (normalized.length() != 40 && normalized.length() != 64) {
            throw new IllegalArgumentException("OpenPGP fingerprint must contain 40 or 64 hexadecimal characters");
        }
        try {
            return HexFormat.of().parseHex(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OpenPGP fingerprint must contain 40 or 64 hexadecimal characters", e);
        }
    }

    private static byte[] utf8(char[] value) {
        if (value == null) {
            return new byte[0];
        }
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return result;
    }

    private static byte[] digest(String algorithm, byte[] value) {
        return messageDigest(algorithm).digest(value);
    }

    private static MessageDigest messageDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireConsumed(Cursor input) {
        if (input.remaining() != 0) {
            throw new IllegalArgumentException("Unexpected data in OpenPGP secret key");
        }
    }

    private static byte[] leftPad(byte[] value, int length) {
        if (value.length > length) {
            throw new IllegalArgumentException("OpenPGP key value is too large");
        }
        byte[] result = new byte[length];
        System.arraycopy(value, 0, result, length - value.length, value.length);
        Arrays.fill(value, (byte) 0);
        return result;
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

    private static byte[] concat(byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }
}
