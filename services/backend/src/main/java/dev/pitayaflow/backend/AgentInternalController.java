package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

/** Credential-bound bridge. A loopback request without its one-attempt grant has no authority. */
@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/internal/agent")
final class AgentInternalController {
    private final SyntheticSourceService sources;
    private final QuestionReviewService reviews;
    AgentInternalController(SyntheticSourceService sources,QuestionReviewService reviews){this.sources=sources;this.reviews=reviews;}
    @GetMapping("/grants/{attempt}/source")
    JsonNode source(@PathVariable String attempt,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @RequestParam("case_id") String caseId,@RequestParam("owner_id") String ownerId) {
        String token=authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):null;
        return sources.authorized(attempt,token,caseId,ownerId);
    }
    @PostMapping("/grants/{attempt}/ready")
    void ready(@PathVariable String attempt,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @RequestParam("case_id") String caseId,@RequestParam("owner_id") String ownerId) {
        String token=authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):null;
        sources.ready(attempt,token,caseId,ownerId);
    }
    @GetMapping("/review/grants/{attempt}/source")
    JsonNode reviewSource(@PathVariable String attempt,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @RequestParam("case_id") String mediaId,@RequestParam("owner_id") String ownerId) {
        return reviews.authorized(attempt,bearer(authorization),mediaId,ownerId);
    }
    @PostMapping("/review/grants/{attempt}/ready")
    void reviewReady(@PathVariable String attempt,@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
        @RequestParam("case_id") String mediaId,@RequestParam("owner_id") String ownerId) {
        reviews.ready(attempt,bearer(authorization),mediaId,ownerId);
    }
    private static String bearer(String value){return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;}
}
