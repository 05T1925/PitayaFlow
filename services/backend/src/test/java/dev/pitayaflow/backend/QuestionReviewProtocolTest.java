package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QuestionReviewProtocolTest {
    @Test void sharedCasesRejectOmittedAndForgedReferences() throws Exception {
        ObjectMapper mapper=new ObjectMapper();JsonNode cases=mapper.readTree(Files.readString(Path.of("../../packages/contracts/s2-review-cases.json")));
        var service=new QuestionReviewService(null,null,mapper,null,null,null,null,"fixed-dsh","http://127.0.0.1:18080","success",true,0);
        List<Map<String,Object>> segments=new ArrayList<>();
        for(JsonNode item:cases.path("source").path("segments")){
            Map<String,Object> segment=new LinkedHashMap<>();segment.put("id",item.path("id").asText());
            segment.put("start_ms",item.path("start_ms").asLong());segment.put("end_ms",item.path("end_ms").asLong());
            segment.put("text",item.path("text").asText());segment.put("role",item.path("role").asText());segments.add(segment);
        }
        var input=new QuestionReviewService.Input("job","source-a",3,"sha",0,0,"outline","media",1,1);
        for(JsonNode item:cases.path("cases")){
            if(item.path("publish_valid").asBoolean())assertNotNull(service.validateDraft(input,item.path("draft"),segments),item.path("name").asText());
            else assertThrows(IllegalStateException.class,()->service.validateDraft(input,item.path("draft"),segments),item.path("name").asText());
        }
    }
}
