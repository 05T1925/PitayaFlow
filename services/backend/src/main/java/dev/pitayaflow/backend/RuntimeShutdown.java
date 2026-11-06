package dev.pitayaflow.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Opt-in local stop marker for the supervised Windows runtime. */
@Component
final class RuntimeShutdown {
    private final String marker;
    private final ConfigurableApplicationContext context;
    RuntimeShutdown(@Value("${pf.shutdown-file:}") String marker, ConfigurableApplicationContext context) {
        this.marker=marker;this.context=context;
    }
    @Scheduled(fixedDelay=500)
    void check() {
        if (!marker.isBlank() && Files.isRegularFile(Path.of(marker))) context.close();
    }
}
