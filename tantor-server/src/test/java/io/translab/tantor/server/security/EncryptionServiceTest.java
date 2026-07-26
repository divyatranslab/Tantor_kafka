package io.translab.tantor.server.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptionServiceTest {

    private static final String SECRET = "Enc_7gR2mK9pL4vN8xQ6tB3wC5zH1sJ0";
    private static final String OTHER_SECRET = "Enc_2xV8mQ4pL7nR1tB9wC6zK3sH5gF0";
    private static final String SALT = "Salt_8qL2nR5vT9x";

    @Test
    void keyDerivationIsDeterministic() {
        assertThat(EncryptionService.deriveKey(SECRET, SALT).getEncoded())
                .containsExactly(EncryptionService.deriveKey(SECRET, SALT).getEncoded());
    }

    @Test
    void differentSecretsProduceDifferentKeys() {
        assertThat(EncryptionService.deriveKey(SECRET, SALT).getEncoded())
                .isNotEqualTo(EncryptionService.deriveKey(OTHER_SECRET, SALT).getEncoded());
    }

    @Test
    void newCiphertextIsVersionedAndRoundTrips() {
        EncryptionService service = new EncryptionService(SECRET, SALT, "");
        String encrypted = service.encrypt("sensitive-value");

        assertThat(encrypted).startsWith("v2:");
        assertThat(service.decrypt(encrypted)).isEqualTo("sensitive-value");
    }

    @Test
    void legacyCiphertextStillDecrypts() throws Exception {
        EncryptionService service = new EncryptionService(OTHER_SECRET, SALT, SECRET);
        String legacyCiphertext = legacyEncrypt("legacy-value", SECRET);

        assertThat(service.decrypt(legacyCiphertext)).isEqualTo("legacy-value");
    }

    private static String legacyEncrypt(String plainText, String secret) throws Exception {
        byte[] key = Arrays.copyOf(secret.getBytes(StandardCharsets.UTF_8), 32);
        byte[] iv = new byte[12];
        Arrays.fill(iv, (byte) 7);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
        payload.put(iv);
        payload.put(encrypted);
        return Base64.getEncoder().encodeToString(payload.array());
    }
}
