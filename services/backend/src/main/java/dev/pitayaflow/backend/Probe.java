package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class Probe {
    private final ObjectMapper mapper;
    private final String executable;
    private final int timeoutSeconds;
    private final boolean faultStreamPackets;
    Probe(ObjectMapper mapper,String executable,int timeoutSeconds) {this(mapper,executable,timeoutSeconds,false);}
    @Autowired Probe(ObjectMapper mapper, @Value("${pf.probe.executable}") String executable,
            @Value("${pf.probe.timeout-seconds}") int timeoutSeconds,
            @Value("${pf.probe.fault-stream-packets:false}") boolean faultStreamPackets) {
        this.mapper = mapper; this.executable = executable; this.timeoutSeconds = timeoutSeconds;this.faultStreamPackets=faultStreamPackets;
    }
    Map<String,Object> inspect(Path path, Duration budget) throws IOException, InterruptedException {
        return inspect(path, budget, () -> false);
    }
    Map<String,Object> inspect(Path path, Duration budget, BooleanSupplier cancelled) throws IOException, InterruptedException {
        long millis = Math.min(budget.toMillis(), timeoutSeconds * 1000L);
        if (millis <= 0) throw ApiError.bad("probe deadline exceeded");
        if (cancelled.getAsBoolean()) throw ApiError.conflict("probe cancelled");
        // Isolated host-fault injection: packet listing fills the pipe while a real ffprobe remains alive.
        java.util.List<String> command=new java.util.ArrayList<>(java.util.List.of(executable,"-v","error","-show_entries",
            faultStreamPackets?"format=format_name,duration:stream=codec_type,index,start_time,channels:packet=pts_time,size":"format=format_name,duration:stream=codec_type,index,start_time,channels",
            "-of","json"));
        if(faultStreamPackets)command.add("-show_packets");
        command.add(path.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true).start();
        try {
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (process.isAlive()) {
                if (cancelled.getAsBoolean()) { terminate(process); throw ApiError.conflict("probe cancelled"); }
                long remaining = until - System.nanoTime();
                if (remaining <= 0) { terminate(process); throw ApiError.bad("probe timeout"); }
                process.waitFor(Math.min(250, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            byte[] output = process.getInputStream().readNBytes(65_537);
            if (process.exitValue() != 0 || output.length > 65_536) throw ApiError.bad("unsupported media");
            JsonNode root = mapper.readTree(output);
            String format = root.path("format").path("format_name").asText("");
            int audioCount=0,firstIndex=0,channels=0;double audioStart=0;
            for (JsonNode stream : root.path("streams")) if (stream.path("codec_type").asText().equals("audio")) {
                if(audioCount==0){firstIndex=stream.path("index").asInt(0);channels=stream.path("channels").asInt(0);
                    try{audioStart=Double.parseDouble(stream.path("start_time").asText("0"));}catch(NumberFormatException ignored){audioStart=0;}}
                audioCount++;
            }
            if (audioCount==0) throw ApiError.bad("audio track required");
            String kind;
            if (format.contains("wav")) kind = "WAV";
            else if (format.contains("mp4") || format.contains("mov")) kind = "MP4";
            else throw ApiError.bad("unsupported media");
            double duration = root.path("format").path("duration").asDouble(-1);
            if (!Double.isFinite(duration) || duration <= 0 || duration > 3600) throw ApiError.bad("invalid duration");
            if(!Double.isFinite(audioStart)||audioStart<0||audioStart>=duration)throw ApiError.bad("audio timeline invalid");
            return Map.of("kind", kind, "duration_seconds", duration, "format", format,"audio_stream_count",audioCount,
                    "audio_stream_index",firstIndex,"audio_start_ms",Math.round(audioStart*1000),"channels",channels);
        } finally {
            if (process.isAlive()) terminate(process);
        }
    }
    private static void terminate(Process process) throws InterruptedException {
        var descendants=process.toHandle().descendants().toList();
        for (var child:descendants) child.destroyForcibly();
        process.destroyForcibly();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime()<until && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
            process.waitFor(20,TimeUnit.MILLISECONDS);
        }
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
            throw new IllegalStateException("probe process did not stop");
    }
}
