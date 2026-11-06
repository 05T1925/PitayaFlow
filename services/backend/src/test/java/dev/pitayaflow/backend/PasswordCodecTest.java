package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PasswordCodecTest {
    @Test void hashesAndRejectsPlaintextFallback() {
        String stored=PasswordCodec.hash("long-random-password");
        assertTrue(stored.startsWith("pbkdf2$210000$"));
        assertTrue(PasswordCodec.matches("long-random-password",stored));
        assertFalse(PasswordCodec.matches("wrong",stored));
        assertFalse(PasswordCodec.matches("legacy", "legacy"));
        assertFalse(PasswordCodec.matches("long-random-password", stored.replace("210000", "1")));
    }
}
