package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class InitializeRequestDeserializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldIgnoreUnknownFieldsInSamplingCapabilities() throws Exception {
        String json = "{"
                + "\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{"
                + "\"sampling\":{"
                + "\"tools\":{}"
                + "}"
                + "},"
                + "\"clientInfo\":{"
                + "\"name\":\"test-client\","
                + "\"version\":\"1.0.0\""
                + "}"
                + "}";

        McpSchema.InitializeRequest request = objectMapper.readValue(json, McpSchema.InitializeRequest.class);

        assertNotNull(request);
        assertNotNull(request.capabilities());
        assertNotNull(request.capabilities().sampling());
    }
}
