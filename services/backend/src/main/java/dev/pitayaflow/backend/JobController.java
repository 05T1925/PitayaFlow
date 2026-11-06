package dev.pitayaflow.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1")
final class JobController {
    private final JobService jobs;
    JobController(JobService jobs) { this.jobs=jobs; }
    private long user(HttpServletRequest req) { return (long)req.getAttribute("pf_user_id"); }
    @PostMapping("/media/{mediaId}/probe") @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> accept(@PathVariable String mediaId,@RequestHeader("Idempotency-Key") String idem,HttpServletRequest req) {
        return jobs.accept(user(req),mediaId,idem);
    }
    @GetMapping("/jobs/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest req) { return jobs.view(id,user(req)); }
    @PostMapping("/jobs/{id}/cancel") Map<String,Object> cancel(@PathVariable String id,HttpServletRequest req) {
        jobs.cancel(id,user(req)); return jobs.view(id,user(req));
    }
}
