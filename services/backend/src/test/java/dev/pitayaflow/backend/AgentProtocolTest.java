package dev.pitayaflow.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentProtocolTest {
    @Test void sharedPositiveAndNegativeCases() throws Exception {
        Path contracts=Path.of("../../packages/contracts").toAbsolutePath();
        var mapper=new ObjectMapper();var protocol=new AgentProtocol(mapper,contracts.toString());
        var cases=mapper.readTree(Files.readString(contracts.resolve("s1-agent-protocol-cases.json")));
        for(var item:cases) assertEquals(item.path("valid").asBoolean(),protocol.valid(item.path("kind").asText(),item.path("value")),item.toString());
    }
}
