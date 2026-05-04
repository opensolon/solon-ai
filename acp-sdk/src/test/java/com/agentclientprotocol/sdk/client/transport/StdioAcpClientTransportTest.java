/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Collections;

/**
 * Test suite for {@link StdioAcpClientTransport}.
 *
 * Note: These tests focus on construction, validation, and serialization behavior.
 * Full integration testing with actual process spawning is handled in integration tests.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class StdioAcpClientTransportTest {

	@Test
	void testConstructorWithValidParameters() {
		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		assertThat(transport).isNotNull();
	}

	@Test
	void testConstructorWithNullParams() {
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		assertThatThrownBy(() -> new StdioAcpClientTransport(null, mapper))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The params can not be null");
	}

	@Test
	void testConstructorWithNullMapper() {
		AgentParameters params = AgentParameters.builder("gemini").build();

		assertThatThrownBy(() -> new StdioAcpClientTransport(params, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The JsonMapper can not be null");
	}

	@Test
	void testProtocolVersions() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		List<Integer> versions = transport.protocolVersions();
		assertThat(versions).isNotNull().isNotEmpty();
		assertThat(versions).contains(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Test
	void testUnmarshalFromWithSimpleObject() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		// Test unmarshaling a simple map to String
		Map<String, Object> data = Collections.singletonMap("value", "test-string");
		String result = transport.unmarshalFrom(data.get("value"), new TypeRef<String>() {
		});

		assertThat(result).isEqualTo("test-string");
	}

	@Test
	void testUnmarshalFromWithComplexObject() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		// Test unmarshaling a map to InitializeResponse
		Map<String, Object> responseData = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("protocolVersion", 1); put("agentCapabilities", Collections.singletonMap("loadSession", true)); put("authMethods", Collections.emptyList()); }});

		AcpSchema.InitializeResponse result = transport.unmarshalFrom(responseData,
				new TypeRef<AcpSchema.InitializeResponse>() {
				});

		assertThat(result).isNotNull();
		assertThat(result.protocolVersion()).isEqualTo(1);
	}

	@Test
	void testUnmarshalFromWithNull() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		String result = transport.unmarshalFrom(null, new TypeRef<String>() {
		});

		assertThat(result).isNull();
	}

	@Test
	void testUnmarshalFromWithTextContent() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		// Create a TextContent-like map
		Map<String, Object> contentData = Collections.unmodifiableMap(new java.util.LinkedHashMap<String, Object>() {{ put("type", "text"); put("text", "Hello World"); }});

		AcpSchema.TextContent result = transport.unmarshalFrom(contentData, new TypeRef<AcpSchema.TextContent>() {
		});

		assertThat(result).isNotNull();
		assertThat(result.text()).isEqualTo("Hello World");
	}

	@Test
	void testMultipleTransportInstancesAreIndependent() {
		AgentParameters params1 = AgentParameters.builder("gemini").arg("--model=1.5-pro").build();
		AgentParameters params2 = AgentParameters.builder("claude").arg("--model=opus").build();

		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport1 = new StdioAcpClientTransport(params1, mapper);
		StdioAcpClientTransport transport2 = new StdioAcpClientTransport(params2, mapper);

		assertThat(transport1).isNotSameAs(transport2);
		assertThat(transport1.protocolVersions()).isEqualTo(transport2.protocolVersions());
	}

	@Test
	void testUnmarshalWithDifferentMappers() {
		AgentParameters params = AgentParameters.builder("gemini").build();

		// Use default mapper
		McpJsonMapper mapper = McpJsonMapper.getDefault();
		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		Map<String, Object> data = Collections.singletonMap("sessionId", "test-123");

		AcpSchema.CancelNotification result = transport.unmarshalFrom(data,
				new TypeRef<AcpSchema.CancelNotification>() {
				});

		assertThat(result).isNotNull();
		assertThat(result.sessionId()).isEqualTo("test-123");
	}

	@Test
	void testTransportSupportsLatestProtocol() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		// Verify that the transport supports the latest protocol version
		assertThat(transport.protocolVersions()).contains(1);
		assertThat(transport.protocolVersions().get(0)).isEqualTo(AcpSchema.LATEST_PROTOCOL_VERSION);
	}

	@Test
	void testUnmarshalPreservesDataTypes() {
		AgentParameters params = AgentParameters.builder("gemini").build();
		McpJsonMapper mapper = McpJsonMapper.getDefault();

		StdioAcpClientTransport transport = new StdioAcpClientTransport(params, mapper);

		// Test that integers, strings, booleans are preserved
		Map<String, Object> complexDataMap = new java.util.LinkedHashMap<>();
		complexDataMap.put("protocolVersion", 1); // integer
		complexDataMap.put("authMethods", Collections.emptyList()); // list
		complexDataMap.put("agentCapabilities", Collections.singletonMap("loadSession", true)); // nested map with boolean
		Map<String, Object> complexData = Collections.unmodifiableMap(complexDataMap);

		AcpSchema.InitializeResponse result = transport.unmarshalFrom(complexData,
				new TypeRef<AcpSchema.InitializeResponse>() {
				});

		assertThat(result.protocolVersion()).isInstanceOf(Integer.class).isEqualTo(1);
		assertThat(result.authMethods()).isInstanceOf(List.class).isEmpty();
	}

}
