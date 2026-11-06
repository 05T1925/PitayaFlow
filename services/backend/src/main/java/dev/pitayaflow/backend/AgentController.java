package dev.pitayaflow.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1")
final class AgentController {
    private final SyntheticSourceService sources;private final AgentService agents;private final JobService jobs;
    AgentController(SyntheticSourceService sources,AgentService agents,JobService jobs){this.sources=sources;this.agents=agents;this.jobs=jobs;}
    private long owner(HttpServletRequest req){return (long)req.getAttribute("pf_user_id");}
    record FixtureRequest(String fixture_id){}
    record AgentRequest(String source_id){}
    @PostMapping("/media/{mediaId}/synthetic-sources") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> create(@PathVariable String mediaId,@RequestBody FixtureRequest request,HttpServletRequest http) throws Exception {
        return sources.create(owner(http),mediaId,request.fixture_id());
    }
    @PostMapping("/synthetic-sources/{id}/revisions") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> revise(@PathVariable String id,@RequestBody FixtureRequest request,HttpServletRequest http) throws Exception {
        return sources.revise(owner(http),id,request.fixture_id());
    }
    @GetMapping("/synthetic-sources/{id}") Map<String,Object> source(@PathVariable String id,HttpServletRequest http) {
        var source=sources.current(owner(http),id);
        return Map.of("source_id",id,"revision",source.revision(),"source_sha256",source.sha256(),"speaker_revision",source.speakerRevision(),"synthetic",true,"content",source.content());
    }
    @GetMapping("/synthetic-sources/{id}/revisions/{revision}") Map<String,Object> sourceVersion(@PathVariable String id,@PathVariable long revision,HttpServletRequest http) {
        var source=sources.version(owner(http),id,revision);
        return Map.of("source_id",id,"revision",source.revision(),"source_sha256",source.sha256(),"speaker_revision",source.speakerRevision(),"synthetic",true,"content",source.content());
    }
    @PostMapping("/media/{mediaId}/synthetic-analysis") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> accept(@PathVariable String mediaId,@RequestBody AgentRequest request,@RequestHeader("Idempotency-Key") String key,HttpServletRequest http) throws Exception {
        return agents.accept(owner(http),mediaId,request.source_id(),key);
    }
}
