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
/*
    * Copyright (c) 2008 Sonatype, Inc. All rights reserved.
    *
    * This program is licensed to you under the Apache License Version 2.0,
    * and you may not use this file except in compliance with the Apache License Version 2.0.
    * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
    *
    * Unless required by applicable law or agreed to in writing,
    * software distributed under the Apache License Version 2.0 is distributed on an
    * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
    */
package com.netflix.tools.jig.internal.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import com.netflix.tools.jig.internal.org.apache.maven.settings.Settings;

/**
 * Decrypts Maven 3 settings credentials using the standard settings security
 * file.
 */
public final class MavenSettingsDecrypter {
    private static final Pattern ENCRYPTED = Pattern.compile(".*?(?<![\\\\$])\\{(.*?(?<!\\\\))\\}.*");
    private static final String MASTER_PASSWORD = "settings.security";
    private static final int KEY_SIZE = 16;
    private static final int SALT_SIZE = 8;

    private MavenSettingsDecrypter() {}

    public static void decrypt(Settings settings, Path securitySettings) {
        String master = null;
        for (var server : settings.getServers()) {
            if (isEncrypted(server.getPassword()) || isEncrypted(server.getPassphrase())) {
                master = master == null ? readMaster(securitySettings) : master;
            }
            server.setPassword(decrypt(server.getPassword(), master));
            server.setPassphrase(decrypt(server.getPassphrase(), master));
        }
        for (var proxy : settings.getProxies()) {
            if (isEncrypted(proxy.getPassword())) {
                master = master == null ? readMaster(securitySettings) : master;
            }
            proxy.setPassword(decrypt(proxy.getPassword(), master));
        }
    }

    private static String readMaster(Path securitySettings) {
        if (!Files.isRegularFile(securitySettings)) {
            throw new IllegalStateException("Maven settings contain encrypted credentials but " + securitySettings + " does not exist");
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream input = Files.newInputStream(securitySettings)) {
            var reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("master")) {
                    String encryptedMaster = reader.getElementText().strip();
                    return decrypt(encryptedMaster, MASTER_PASSWORD);
                }
            }
        } catch (IOException | XMLStreamException e) {
            throw new IllegalStateException("Failed to read Maven settings security from " + securitySettings, e);
        }
        throw new IllegalStateException("Maven settings security has no master password: " + securitySettings);
    }

    private static boolean isEncrypted(String value) {
        return value != null && ENCRYPTED.matcher(value).matches();
    }

    private static String decrypt(String value, String password) {
        if (!isEncrypted(value)) {
            return value;
        }
        Matcher matcher = ENCRYPTED.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        try {
            return decryptBase64(matcher.group(1), password);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt Maven settings credentials", e);
        }
    }

    private static String decryptBase64(String value, String password) throws Exception {
        byte[] material = Base64.getDecoder().decode(value.getBytes(StandardCharsets.UTF_8));
        if (material.length <= SALT_SIZE) {
            throw new IllegalArgumentException("Invalid encrypted credential");
        }
        byte[] salt = new byte[SALT_SIZE];
        System.arraycopy(material, 0, salt, 0, SALT_SIZE);
        int padding = Byte.toUnsignedInt(material[SALT_SIZE]);
        int encryptedLength = material.length - SALT_SIZE - 1 - padding;
        if (encryptedLength <= 0) {
            throw new IllegalArgumentException("Invalid encrypted credential");
        }
        byte[] encrypted = new byte[encryptedLength];
        System.arraycopy(material, SALT_SIZE + 1, encrypted, 0, encrypted.length);
        Cipher cipher = createCipher(password.getBytes(StandardCharsets.UTF_8), salt, Cipher.DECRYPT_MODE);
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static Cipher createCipher(byte[] password, byte[] salt, int mode) throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException, InvalidAlgorithmParameterException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyAndIv = new byte[KEY_SIZE * 2];
        byte[] result = null;
        int position = 0;
        while (position < keyAndIv.length) {
            digest.update(password);
            digest.update(salt, 0, SALT_SIZE);
            result = digest.digest();
            int length = Math.min(result.length, keyAndIv.length - position);
            System.arraycopy(result, 0, keyAndIv, position, length);
            position += length;
            if (position < keyAndIv.length) {
                digest.reset();
                digest.update(result);
            }
        }
        byte[] key = new byte[KEY_SIZE];
        byte[] iv = new byte[KEY_SIZE];
        System.arraycopy(keyAndIv, 0, key, 0, key.length);
        System.arraycopy(keyAndIv, key.length, iv, 0, iv.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher;
    }
}
