package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1/media/{mediaId}/review")
final class QuestionReviewController {
    private final QuestionReviewService service;
    private final PracticeService practice;
    QuestionReviewController(QuestionReviewService service,PracticeService practice){this.service=service;this.practice=practice;}
    private long owner(HttpServletRequest request){return (long)request.getAttribute("pf_user_id");}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> accept(@PathVariable String mediaId,@RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        return service.accept(owner(request),mediaId,key);
    }
    @GetMapping("/outline") Map<String,Object> outline(@PathVariable String mediaId,HttpServletRequest request){
        return service.outline(owner(request),mediaId);
    }
    @PutMapping("/outline") Map<String,Object> revise(@PathVariable String mediaId,@RequestBody JsonNode body,HttpServletRequest request){
        return service.reviseOutline(owner(request),mediaId,body);
    }
    @GetMapping List<Map<String,Object>> reports(@PathVariable String mediaId,HttpServletRequest request){
        return service.reports(owner(request),mediaId);
    }
    @GetMapping("/practice") List<PracticeService.Answer> practice(@PathVariable String mediaId,HttpServletRequest request){
        return practice.list(owner(request),mediaId);
    }
    @GetMapping("/{taskId}") Map<String,Object> report(@PathVariable String mediaId,@PathVariable String taskId,HttpServletRequest request){
        return service.report(owner(request),mediaId,taskId);
    }
    @PutMapping("/{taskId}/practice/{questionId}") Map<String,Object> savePractice(@PathVariable String mediaId,@PathVariable String taskId,
            @PathVariable String questionId,@RequestBody JsonNode body,HttpServletRequest request){
        if(!body.path("expected_revision").canConvertToLong()||!body.path("text").isTextual())throw ApiError.bad("PRACTICE_INPUT_INVALID");
        if(Boolean.TRUE.equals(service.report(owner(request),mediaId,taskId).get("stale")))throw ApiError.conflict("PRACTICE_SOURCE_STALE");
        return practice.save(owner(request),mediaId,taskId,questionId,body.path("expected_revision").asLong(),body.path("text").asText());
    }
    @GetMapping(value="/{taskId}/export",produces="text/plain; charset=UTF-8") ResponseEntity<byte[]> export(
            @PathVariable String mediaId,@PathVariable String taskId,HttpServletRequest request){
        byte[] bytes=service.export(owner(request),mediaId,taskId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .contentType(new MediaType("text","plain",StandardCharsets.UTF_8)).body(bytes);
    }
}
