package io.translab.tantor.server.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_DERIVATION = "PBKDF2WithHmacSHA256";
    private static final String V2_PREFIX = "v2:";
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKey v2Key;
    private final SecretKey legacyKey;

    public EncryptionService(
            @Value("${tantor.security.encryption.key}") String encryptionKey,
            @Value("${tantor.security.encryption.salt}") String encryptionSalt,
            @Value("${tantor.security.encryption.legacy-key:}") String legacyEncryptionKey) {
        this.v2Key = deriveKey(encryptionKey, encryptionSalt);
        this.legacyKey = deriveLegacyKey(
                legacyEncryptionKey == null || legacyEncryptionKey.isBlank()
                        ? encryptionKey
                        : legacyEncryptionKey);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }
        try {
            return V2_PREFIX + encryptPayload(plainText, v2Key);
        } catch (GeneralSecurityException e) {
            log.error("Failed to encrypt data");
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return encryptedText;
        }
        try {
            if (encryptedText.startsWith(V2_PREFIX)) {
                return decryptPayload(encryptedText.substring(V2_PREFIX.length()), v2Key);
            }
            // Compatibility path for values encrypted before versioned PBKDF2
            // derivation was introduced. All newly encrypted values use v2.
            return decryptPayload(encryptedText, legacyKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.error("Failed to decrypt data");
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    static SecretKey deriveKey(String secret, String salt) {
        PBEKeySpec spec = new PBEKeySpec(
                secret.toCharArray(),
                salt.getBytes(StandardCharsets.UTF_8),
                PBKDF2_ITERATIONS,
                AES_KEY_BITS);
        try {
            byte[] encoded = SecretKeyFactory.getInstance(KEY_DERIVATION).generateSecret(spec).getEncoded();
            return new SecretKeySpec(encoded, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption key derivation failed", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static SecretKey deriveLegacyKey(String secret) {
        byte[] source = secret.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(Arrays.copyOf(source, AES_KEY_BITS / Byte.SIZE), "AES");
    }

    private static String encryptPayload(String plainText, SecretKey key) throws GeneralSecurityException {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        ByteBuffer payload = ByteBuffer.allocate(iv.length + cipherText.length);
        payload.put(iv);
        payload.put(cipherText);
        return Base64.getEncoder().encodeToString(payload.array());
    }

    private static String decryptPayload(String encodedPayload, SecretKey key) throws GeneralSecurityException {
        byte[] cipherMessage = Base64.getDecoder().decode(encodedPayload);
        if (cipherMessage.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Encrypted payload is invalid");
        }

        ByteBuffer payload = ByteBuffer.wrap(cipherMessage);
        byte[] iv = new byte[GCM_IV_LENGTH];
        payload.get(iv);
        byte[] cipherText = new byte[payload.remaining()];
        payload.get(cipherText);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }
}
