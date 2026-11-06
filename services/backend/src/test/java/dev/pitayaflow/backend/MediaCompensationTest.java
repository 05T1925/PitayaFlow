package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class MediaCompensationTest {
    @TempDir Path directory;
    @Test void unknownCommitDoesNotBlindlyRemoveAnObject() throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);
        ObjectStore objects=mock(ObjectStore.class);
        Probe probe=mock(Probe.class);
        TransactionTemplate tx=mock(TransactionTemplate.class);
        when(probe.inspect(any(),any(Duration.class))).thenReturn(Map.of("kind","WAV","duration_seconds",1.0));
        doThrow(new IllegalStateException("injected db failure")).when(tx).executeWithoutResult(any());
        var service=new MediaService(db,objects,probe,tx,new ProbeTempFiles(directory.toString()));
        var file=new MockMultipartFile("file","untrusted.wav","audio/wav",new byte[]{1,2,3});
        assertThrows(IllegalStateException.class,()->service.upload(1,file));
        verify(objects).put(any(),any());
        verify(objects,never()).remove(any());
        verify(db,never()).update(startsWith("DELETE FROM pf_object_intent"),any(String.class));
    }
}
