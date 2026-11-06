package dev.pitayaflow.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Both runtimes read packages/contracts/s1-agent-protocol.json; this is its small common type vocabulary. */
@Component
final class AgentProtocol {
    private final JsonNode schema;
    AgentProtocol(ObjectMapper mapper,@Value("${pf.agent.contracts-dir:packages/contracts}") String directory) throws Exception {
        schema=mapper.readTree(Files.readString(Path.of(directory,"s1-agent-protocol.json")));
    }
    boolean valid(String kind,JsonNode value) {
        JsonNode section=schema.path(kind);
        if (section.isMissingNode() || !fields(value,section.path("required"))) return false;
        return !section.has("citation_required") || fields(value.path("citation"),section.path("citation_required"));
    }
    private boolean fields(JsonNode value,JsonNode fields) {
        if (!value.isObject() || !fields.isObject()) return false;
        var entries=fields.fields();
        while(entries.hasNext()) {
            var item=entries.next();JsonNode field=value.path(item.getKey());
            boolean okay=switch(item.getValue().asText()) {
                case "string" -> field.isTextual() && !field.asText().isBlank();
                case "integer" -> field.isIntegralNumber() && field.canConvertToLong() && field.asLong()>=0 && field.asLong()<=9_007_199_254_740_991L;
                case "array" -> field.isArray();
                default -> field.isObject();
            };
            if(!okay) return false;
        }
        return true;
    }
}
