package dev.pitayaflow.backend;

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
@RequestMapping("/api/v1/media/{mediaId}/transcript")
final class TranscriptController {
    private final TranscriptService service;
    private final TranscriptEvents events;
    TranscriptController(TranscriptService service,TranscriptEvents events){this.service=service;this.events=events;}
    private long owner(HttpServletRequest req){return (long)req.getAttribute("pf_user_id");}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> accept(@PathVariable String mediaId,@RequestHeader("Idempotency-Key") String idem,HttpServletRequest req){
        return service.accept(owner(req),mediaId,idem);
    }
    @GetMapping Map<String,Object> get(@PathVariable String mediaId,@RequestParam(required=false) Long revision,HttpServletRequest req){
        return service.get(owner(req),mediaId,revision);
    }
    @PutMapping Map<String,Object> revise(@PathVariable String mediaId,@RequestBody TranscriptService.Revision input,HttpServletRequest req){
        return service.revise(owner(req),mediaId,input);
    }
    @GetMapping(value="/jobs/{taskId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable String mediaId,@PathVariable String taskId,
            @RequestHeader(value="Last-Event-ID",required=false) String last,@RequestParam(required=false,defaultValue="0") long after,HttpServletRequest req){
        long cursor=after;
        if(last!=null&&!last.isBlank())try{cursor=Math.max(cursor,Long.parseLong(last));}catch(NumberFormatException bad){throw ApiError.bad("INVALID_EVENT_CURSOR");}
        if(cursor<0)throw ApiError.bad("INVALID_EVENT_CURSOR");return events.stream(owner(req),mediaId,taskId,cursor);
    }
    @GetMapping(value="/export",produces="text/plain; charset=UTF-8") ResponseEntity<byte[]> export(@PathVariable String mediaId,
            @RequestParam(required=false) Long revision,HttpServletRequest req){
        var data=service.get(owner(req),mediaId,revision);
        @SuppressWarnings("unchecked") List<TranscriptService.Segment> segments=(List<TranscriptService.Segment>)data.get("segments");
        var content=new StringBuilder("# revision ").append(data.get("revision")).append(" source ").append(data.get("source_sha256"))
                .append(" speaker_revision ").append(data.get("speaker_revision")).append('\n');
        for(var segment:segments)content.append('[').append(segment.startMs()).append('-').append(segment.endMs()).append(" ms] ")
                .append(segment.role()).append('/').append(segment.speakerId()).append(" [revision ").append(data.get("revision")).append("] ")
                .append(segment.text().replace('\n',' ').replace('\r',' ')).append('\n');
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .contentType(new MediaType("text","plain",StandardCharsets.UTF_8)).body(content.toString().getBytes(StandardCharsets.UTF_8));
    }
}
