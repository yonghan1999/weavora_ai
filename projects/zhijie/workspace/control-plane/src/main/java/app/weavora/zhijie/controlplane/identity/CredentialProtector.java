package app.weavora.zhijie.controlplane.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class CredentialProtector {
    private final Argon2PasswordEncoder passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 16384, 2);
    private final SecureRandom secureRandom = new SecureRandom();

    public String hashPassword(String password) {
        return passwordEncoder.encode(password);
    }

    public boolean matchesPassword(String password, String encoded) {
        return passwordEncoder.matches(password, encoded);
    }

    public String randomSecret() {
        byte[] value = new byte[32];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String randomCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    public String hashToken(String token) {
        return digest("token:" + token);
    }

    public String hashChallenge(String userId, ChallengePurpose purpose, String code) {
        return digest("challenge:" + userId + ":" + purpose + ":" + code);
    }

    private String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
