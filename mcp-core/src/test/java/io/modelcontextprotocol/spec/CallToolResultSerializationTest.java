package io.modelcontextprotocol.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallToolResultSerializationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldOmitStructuredContentWhenItIsEmptyMap() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")),
                false,
                Map.of(),
                null);

        String json = objectMapper.writeValueAsString(result);

        assertFalse(json.contains("\"structuredContent\""));
    }

    @Test
    void shouldKeepStructuredContentWhenItIsNotEmpty() throws Exception {
        McpSchema.CallToolResult result = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("ok")),
                false,
                Map.of("code", 200),
                null);

        String json = objectMapper.writeValueAsString(result);

        assertTrue(json.contains("\"structuredContent\""));
        assertTrue(json.contains("\"code\":200"));
    }
}
