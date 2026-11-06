package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class ProbeTest {
    @TempDir Path dir;
    private final Probe probe=new Probe(new ObjectMapper(),"ffprobe",12);
    @Test void acceptsRealWavAndAudioMp4RejectsDisguisedBytes() throws Exception {
        Path wav=dir.resolve("tone.wav"), mp4=dir.resolve("tone.mp4"), fake=dir.resolve("fake.wav");
        assertEquals(0,new ProcessBuilder("ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=1","-y",wav.toString()).start().waitFor());
        assertEquals(0,new ProcessBuilder("ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=1","-c:a","aac","-y",mp4.toString()).start().waitFor());
        assertEquals("WAV",probe.inspect(wav,Duration.ofSeconds(3)).get("kind"));
        assertEquals("MP4",probe.inspect(mp4,Duration.ofSeconds(3)).get("kind"));
        Files.writeString(fake,"not an audio file");
        assertThrows(ApiError.class,()->probe.inspect(fake,Duration.ofSeconds(3)));
    }
    @Test void cancelledProbeCannotReturnResult() throws Exception {
        Path wav=dir.resolve("tone.wav");
        assertEquals(0,new ProcessBuilder("ffmpeg","-v","error","-f","lavfi","-i","sine=frequency=440:duration=1","-y",wav.toString()).start().waitFor());
        assertThrows(ApiError.class,()->probe.inspect(wav,Duration.ofSeconds(3),()->true));
    }
    @Test void runningProbeIsStoppedWithinBoundAfterCancellation() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path slow=dir.resolve("slow.cmd");
        Files.writeString(slow,"@echo off\r\nping -n 30 127.0.0.1 >nul\r\n");
        Probe waiting=new Probe(new ObjectMapper(),slow.toString(),12);
        AtomicBoolean cancel=new AtomicBoolean();
        try(var executor=Executors.newSingleThreadExecutor()) {
            long start=System.nanoTime();
            var future=executor.submit(()->waiting.inspect(slow,Duration.ofSeconds(12),cancel::get));
            Thread.sleep(300);
            cancel.set(true);
            ExecutionException error=assertThrows(ExecutionException.class,()->future.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ApiError.class,error.getCause());
            assertEquals("probe cancelled",((ApiError)error.getCause()).getReason());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<5);
        }
    }
    @Test void deadlineStopsSlowProbeAndItsChild() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path slow=dir.resolve("timeout.cmd");
        Files.writeString(slow,"@echo off\r\nping -n 30 127.0.0.1 >nul\r\n");
        Probe waiting=new Probe(new ObjectMapper(),slow.toString(),12);
        long start=System.nanoTime();
        ApiError error=assertThrows(ApiError.class,()->waiting.inspect(slow,Duration.ofMillis(400)));
        assertEquals("probe timeout",error.getReason());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<5);
    }
}
