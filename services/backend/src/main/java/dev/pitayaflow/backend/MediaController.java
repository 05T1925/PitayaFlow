package dev.pitayaflow.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1/media")
final class MediaController {
    private final MediaService service;
    MediaController(MediaService service) { this.service = service; }
    private long user(HttpServletRequest req) { return (long) req.getAttribute("pf_user_id"); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> upload(@RequestPart("file") MultipartFile file, HttpServletRequest req) throws Exception { return service.upload(user(req), file); }
    @GetMapping List<Map<String,Object>> list(HttpServletRequest req) { return service.list(user(req)); }
    @GetMapping("/{id}") Map<String,Object> get(@PathVariable String id, HttpServletRequest req) { return service.owned(id,user(req)).publicView(); }
    @GetMapping({"/{id}/download","/{id}/play"}) ResponseEntity<StreamingResponseBody> play(@PathVariable String id,
            @RequestHeader(value="Range",required=false) String range,HttpServletRequest req) {
        long owner=user(req);var media=service.owned(id,owner);long size=media.size();
        long start=0,end=size-1;boolean partial=range!=null;
        if(partial){
            var match=java.util.regex.Pattern.compile("^bytes=(\\d*)-(\\d*)$").matcher(range);
            if(!match.matches() || (match.group(1).isEmpty() && match.group(2).isEmpty()))throw new ApiError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"invalid range");
            try{
                if(match.group(1).isEmpty()) {long suffix=Long.parseLong(match.group(2));if(suffix<=0)throw new NumberFormatException();start=Math.max(0,size-suffix);}
                else {start=Long.parseLong(match.group(1));if(!match.group(2).isEmpty())end=Math.min(size-1,Long.parseLong(match.group(2)));}
            }catch(NumberFormatException error){throw new ApiError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"invalid range");}
            if(start>=size||end<start)throw new ApiError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"invalid range");
        }
        long offset=start,length=end-start+1;
        StreamingResponseBody body=output->{
            // Check current owner/status again when the async stream starts; no signed/public object URL.
            var current=service.owned(id,owner);
            try(var input=service.openRange(current.objectKey(),offset,length)){input.transferTo(output);}
        };
        var response=ResponseEntity.status(partial?HttpStatus.PARTIAL_CONTENT:HttpStatus.OK)
                .header(HttpHeaders.ACCEPT_RANGES,"bytes").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,"inline")
                .contentType("MP4".equals(media.kind())?MediaType.parseMediaType("video/mp4"):MediaType.parseMediaType("audio/wav"))
                .contentLength(length);
        if(partial)response.header(HttpHeaders.CONTENT_RANGE,"bytes "+start+"-"+end+"/"+size);
        return response.body(body);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id, HttpServletRequest req) { service.delete(id,user(req)); }
}
