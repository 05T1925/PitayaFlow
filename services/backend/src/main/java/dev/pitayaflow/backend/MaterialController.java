package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1")
final class MaterialController {
    private final MaterialService materials;
    MaterialController(MaterialService materials){this.materials=materials;}
    private long owner(HttpServletRequest request){return (long)request.getAttribute("pf_user_id");}
    @GetMapping("/materials") List<Map<String,Object>> list(HttpServletRequest request){return materials.list(owner(request));}
    @PostMapping("/materials/paste") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> paste(@RequestBody JsonNode body,HttpServletRequest request){
        if(!body.path("name").isTextual()||!body.path("text").isTextual())throw ApiError.bad("MATERIAL_INPUT_INVALID");
        return materials.ingest(owner(request),body.path("name").asText(),"PASTE",body.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),"paste");
    }
    @PostMapping(value="/materials",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> upload(@RequestPart("file") MultipartFile file,HttpServletRequest request) throws Exception{
        String name=file.getOriginalFilename();String kind=kind(name);
        if(file.getSize()>2L*1024*1024||file.isEmpty())throw ApiError.bad("MATERIAL_INPUT_INVALID");
        return materials.ingest(owner(request),name,kind,file.getBytes(),name);
    }
    @PutMapping(value="/materials/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String,Object> revise(@PathVariable String id,@RequestParam long expectedRevision,@RequestPart("file") MultipartFile file,HttpServletRequest request) throws Exception{
        if(file.getSize()>2L*1024*1024||file.isEmpty())throw ApiError.bad("MATERIAL_INPUT_INVALID");
        return materials.revise(owner(request),id,expectedRevision,file.getBytes(),file.getOriginalFilename());
    }
    @PutMapping("/materials/{id}/paste") Map<String,Object> revisePaste(@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest request){
        if(!body.path("expected_revision").canConvertToLong()||!body.path("text").isTextual())throw ApiError.bad("MATERIAL_INPUT_INVALID");
        return materials.revise(owner(request),id,body.path("expected_revision").asLong(),
                body.path("text").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8),"paste");
    }
    @DeleteMapping("/materials/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id,HttpServletRequest request){materials.delete(owner(request),id);}
    @GetMapping("/media/{mediaId}/materials") Map<String,Object> selected(@PathVariable String mediaId,HttpServletRequest request){
        long owner=owner(request);var state=materials.selection(owner,mediaId,false);
        return Map.of("revision",state.revision(),"materials",state.materials());
    }
    @PutMapping("/media/{mediaId}/materials") Map<String,Object> select(@PathVariable String mediaId,@RequestBody JsonNode body,HttpServletRequest request){
        if(!body.path("expected_revision").canConvertToLong()||!body.path("material_ids").isArray())throw ApiError.bad("MATERIAL_SELECTION_INVALID");
        List<String> ids=new java.util.ArrayList<>();for(JsonNode item:body.path("material_ids")){
            if(!item.isTextual()||!item.asText().matches("[0-9a-f-]{36}"))throw ApiError.bad("MATERIAL_SELECTION_INVALID");ids.add(item.asText());
        }
        return materials.select(owner(request),mediaId,body.path("expected_revision").asLong(),ids);
    }
    private static String kind(String name){if(name==null)throw ApiError.bad("MATERIAL_TYPE_UNSUPPORTED");
        String lower=name.toLowerCase(java.util.Locale.ROOT);
        if(lower.endsWith(".txt"))return "TXT";if(lower.endsWith(".md"))return "MD";
        if(lower.endsWith(".pdf"))return "PDF";if(lower.endsWith(".docx"))return "DOCX";
        throw ApiError.bad("MATERIAL_TYPE_UNSUPPORTED");
    }
}
