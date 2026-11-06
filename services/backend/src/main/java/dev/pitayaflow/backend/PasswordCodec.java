package dev.pitayaflow.backend;

// Adapted from LinkVideo f5451c4, server/service/AuthService.java (MIT, Copyright 2026 Majst): PBKDF2 only; legacy plain-text fallback removed.
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

final class PasswordCodec {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    static String hash(String raw) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().withoutPadding().encodeToString(salt)
                + "$" + Base64.getEncoder().withoutPadding().encodeToString(derive(raw, salt, ITERATIONS));
    }
    static boolean matches(String raw, String stored) {
        if (raw == null || stored == null) return false;
        try {
            String[] parts = stored.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2")) return false;
            int count = Integer.parseInt(parts[1]);
            if (count < ITERATIONS || count > 600_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return salt.length == 16 && expected.length == 32 && MessageDigest.isEqual(expected, derive(raw, salt, count));
        } catch (RuntimeException e) { return false; }
    }
    private static byte[] derive(String raw, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception e) { throw new IllegalStateException("password hash unavailable", e); }
        finally { spec.clearPassword(); }
    }
    private PasswordCodec() {}
}
