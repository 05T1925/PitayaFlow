package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeTempFilesTest {
    @TempDir Path directory;
    @Test void startupOnlyDeletesPrivateCopiesWhoseOwnerProcessEnded() throws Exception {
        var files=new ProbeTempFiles(directory.toString());
        Path live=files.create();
        Path dead=directory.resolve("pid-999999999-dummy.bin");
        Path unrelated=directory.resolve("notes.bin");
        Files.writeString(dead,"synthetic"); Files.writeString(unrelated,"keep");
        files.removeDeadOwners();
        assertTrue(Files.exists(live));
        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(unrelated));
    }
    @Test void cleanupFailurePreservesPrimaryErrorAndRetries() throws Exception {
        var files=new ProbeTempFiles(directory.toString());
        Path blocked=Files.createDirectory(directory.resolve("blocked.bin"));
        Path child=Files.writeString(blocked.resolve("child"),"synthetic");
        var primary=new IllegalStateException("original failure");
        files.cleanup(blocked,primary);
        assertEquals("original failure",primary.getMessage());
        assertEquals(1,primary.getSuppressed().length);
        assertTrue(Files.exists(blocked));
        Files.delete(child);files.retryPending();
        assertFalse(Files.exists(blocked));
    }
}
