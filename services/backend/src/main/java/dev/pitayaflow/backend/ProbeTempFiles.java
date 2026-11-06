package dev.pitayaflow.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
final class ProbeTempFiles {
    private static final Logger log=LoggerFactory.getLogger(ProbeTempFiles.class);
    private static final Pattern NAME=Pattern.compile("pid-([0-9]+)-[A-Za-z0-9_-]+\\.bin");
    private final Path directory;
    private final Set<Path> pending=ConcurrentHashMap.newKeySet();
    ProbeTempFiles(@Value("${pf.probe.work-dir:}") String configured) throws IOException {
        directory=configured.isBlank() ? Path.of(System.getProperty("java.io.tmpdir"),"pitayaflow-s1-probe") : Path.of(configured);
        Files.createDirectories(directory);
        try { Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) { /* Windows ACL follows the user's temp directory. */ }
    }
    Path create() throws IOException {
        return Files.createTempFile(directory,"pid-"+ProcessHandle.current().pid()+"-",".bin");
    }
    void cleanup(Path file,Throwable primary) {
        if (file==null) return;
        try { Files.deleteIfExists(file); pending.remove(file); }
        catch (IOException error) {
            pending.add(file);
            if (primary!=null) primary.addSuppressed(error);
            log.warn("private_temp_cleanup_retry exception_type={}",error.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay=10_000,initialDelay=10_000)
    void retryPending() { for (Path file:pending) cleanup(file,null); }
    @EventListener(ApplicationReadyEvent.class)
    void removeDeadOwners() throws IOException {
        try (var files=Files.list(directory)) {
            for (Path file:files.toList()) {
                var match=NAME.matcher(file.getFileName().toString());
                if (!match.matches()) continue;
                long pid=Long.parseLong(match.group(1));
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                Files.deleteIfExists(file);
            }
        }
    }
}
