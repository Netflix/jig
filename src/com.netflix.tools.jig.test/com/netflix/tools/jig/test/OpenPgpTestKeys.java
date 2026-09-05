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
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class OpenPgpTestKeys {
    private static final String PASSPHRASE = "TEST";
    private static final Material RSA = rsa();

    record Material(byte[] armored, String fingerprint) {
        Material {
            armored = armored.clone();
        }

        @Override
        public byte[] armored() {
            return armored.clone();
        }

        String armoredText() {
            return new String(armored, StandardCharsets.US_ASCII);
        }
    }

    private OpenPgpTestKeys() {}

    static Material encryptedRsa() {
        return RSA;
    }

    private static Material rsa() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var publicKey = (RSAPublicKey) pair.getPublic();
            var privateKey = (RSAPrivateCrtKey) pair.getPrivate();

            var publicBody = new ByteArrayOutputStream();
            publicBody.write(4);
            writeU32(publicBody, Instant.parse("2026-01-02T03:04:05Z")
                    .getEpochSecond());
            publicBody.write(1);
            writeMpi(publicBody, publicKey.getModulus());
            writeMpi(publicBody, publicKey.getPublicExponent());
            byte[] encodedPublic = publicBody.toByteArray();
            byte[] fingerprintInput = new byte[encodedPublic.length + 3];
            fingerprintInput[0] = (byte) 0x99;
            fingerprintInput[1] = (byte) (encodedPublic.length >>> 8);
            fingerprintInput[2] = (byte) encodedPublic.length;
            System.arraycopy(encodedPublic, 0, fingerprintInput, 3, encodedPublic.length);
            String fingerprint = HexFormat.of()
                    .withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-1")
                            .digest(fingerprintInput));

            var secret = new ByteArrayOutputStream();
            writeMpi(secret, privateKey.getPrivateExponent());
            writeMpi(secret, privateKey.getPrimeP());
            writeMpi(secret, privateKey.getPrimeQ());
            writeMpi(secret, privateKey.getPrimeP()
                    .modInverse(privateKey.getPrimeQ()));
            byte[] secretMaterial = secret.toByteArray();
            byte[] integrity = MessageDigest.getInstance("SHA-1").digest(secretMaterial);
            byte[] clear = concat(secretMaterial, integrity);

            var random = new SecureRandom();
            byte[] salt = new byte[8];
            byte[] iv = new byte[16];
            random.nextBytes(salt);
            random.nextBytes(iv);
            byte[] key = s2k(salt, PASSPHRASE.getBytes(StandardCharsets.UTF_8), 65_536);
            var cipher = Cipher.getInstance("AES/CFB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(clear);

            var body = new ByteArrayOutputStream();
            body.writeBytes(encodedPublic);
            body.write(254);
            body.write(9);
            body.write(3);
            body.write(10);
            body.writeBytes(salt);
            body.write(96);
            body.writeBytes(iv);
            body.writeBytes(encrypted);
            Arrays.fill(secretMaterial, (byte) 0);
            Arrays.fill(clear, (byte) 0);
            Arrays.fill(key, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
            return new Material(armor(packet(5, body.toByteArray())), fingerprint);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] s2k(byte[] salt, byte[] passphrase, int count) throws Exception {
        byte[] material = concat(salt, passphrase);
        var digest = MessageDigest.getInstance("SHA-512");
        int remaining = count;
        while (remaining > 0) {
            int length = Math.min(remaining, material.length);
            digest.update(material, 0, length);
            remaining -= length;
        }
        return Arrays.copyOf(digest.digest(), 32);
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

    private static void writeMpi(ByteArrayOutputStream output, BigInteger value) {
        byte[] magnitude = value.toByteArray();
        int offset = magnitude.length > 1 && magnitude[0] == 0
                ? 1
                : 0;
        writeU16(output, value.bitLength());
        output.write(magnitude, offset, magnitude.length - offset);
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

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
