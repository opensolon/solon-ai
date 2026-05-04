/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk.client.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Collections;

/**
 * Test suite for {@link AgentParameters} builder and configuration.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 */
class AgentParametersTest {

	@Test
	void testBuilderWithCommand() {
		AgentParameters params = AgentParameters.builder("gemini").build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).isEmpty();
		assertThat(params.getEnv()).isNotEmpty(); // Should have default env vars
	}

	@Test
	void testBuilderWithNullCommand() {
		assertThatThrownBy(() -> AgentParameters.builder(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The command can not be null");
	}

	@Test
	void testBuilderWithSingleArg() {
		AgentParameters params = AgentParameters.builder("gemini").arg("--experimental-acp").build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).containsExactly("--experimental-acp");
	}

	@Test
	void testBuilderWithMultipleArgs() {
		AgentParameters params = AgentParameters.builder("gemini")
			.arg("--experimental-acp")
			.arg("--model")
			.arg("gemini-1.5-pro")
			.build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).containsExactly("--experimental-acp", "--model", "gemini-1.5-pro");
	}

	@Test
	void testBuilderWithArgsVarargs() {
		AgentParameters params = AgentParameters.builder("gemini")
			.args("--experimental-acp", "--model", "gemini-1.5-pro")
			.build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).containsExactly("--experimental-acp", "--model", "gemini-1.5-pro");
	}

	@Test
	void testBuilderWithArgsList() {
		List<String> argsList = Arrays.asList("--experimental-acp", "--model", "gemini-1.5-pro");
		AgentParameters params = AgentParameters.builder("gemini").args(argsList).build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).containsExactly("--experimental-acp", "--model", "gemini-1.5-pro");
		// Verify it's a copy, not the original list
		assertThat(params.getArgs()).isNotSameAs(argsList);
	}

	@Test
	void testBuilderWithNullArg() {
		assertThatThrownBy(() -> AgentParameters.builder("gemini").arg(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The arg can not be null");
	}

	@Test
	void testBuilderWithNullArgsVarargs() {
		assertThatThrownBy(() -> AgentParameters.builder("gemini").args((String[]) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The args can not be null");
	}

	@Test
	void testBuilderWithNullArgsList() {
		assertThatThrownBy(() -> AgentParameters.builder("gemini").args((List<String>) null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The args can not be null");
	}

	@Test
	void testBuilderWithEnvironmentVariable() {
		AgentParameters params = AgentParameters.builder("gemini").addEnvVar("API_KEY", "test-key-123").build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getEnv()).containsEntry("API_KEY", "test-key-123");
	}

	@Test
	void testBuilderWithMultipleEnvironmentVariables() {
		AgentParameters params = AgentParameters.builder("gemini")
			.addEnvVar("API_KEY", "test-key-123")
			.addEnvVar("MODEL", "gemini-1.5-pro")
			.addEnvVar("DEBUG", "true")
			.build();

		assertThat(params.getEnv()).containsEntry("API_KEY", "test-key-123")
			.containsEntry("MODEL", "gemini-1.5-pro")
			.containsEntry("DEBUG", "true");
	}

	@Test
	void testBuilderWithEnvironmentMap() {
		Map<String, String> envMap = new java.util.LinkedHashMap<>();
		envMap.put("API_KEY", "test-key-123");
		envMap.put("MODEL", "gemini-1.5-pro");
		envMap = Collections.unmodifiableMap(envMap);

		AgentParameters params = AgentParameters.builder("gemini").env(envMap).build();

		assertThat(params.getEnv()).containsEntry("API_KEY", "test-key-123").containsEntry("MODEL", "gemini-1.5-pro");
	}

	@Test
	void testBuilderWithNullEnvKey() {
		assertThatThrownBy(() -> AgentParameters.builder("gemini").addEnvVar(null, "value"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The key can not be null");
	}

	@Test
	void testBuilderWithNullEnvValue() {
		assertThatThrownBy(() -> AgentParameters.builder("gemini").addEnvVar("KEY", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("The value can not be null");
	}

	@Test
	void testBuilderWithNullEnvMap() {
		// Null env map should be handled gracefully (no exception)
		AgentParameters params = AgentParameters.builder("gemini").env(null).build();

		assertThat(params.getEnv()).isNotEmpty(); // Should still have default env vars
	}

	@Test
	void testBuilderWithEmptyEnvMap() {
		// Empty env map should be handled gracefully (no exception)
		AgentParameters params = AgentParameters.builder("gemini").env(Collections.emptyMap()).build();

		assertThat(params.getEnv()).isNotEmpty(); // Should still have default env vars
	}

	@Test
	void testDefaultEnvironmentVariablesIncluded() {
		AgentParameters params = AgentParameters.builder("gemini").build();

		// Should include safe default environment variables like PATH, HOME, USER, etc.
		assertThat(params.getEnv()).isNotEmpty();

		// PATH should always be included
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.contains("win")) {
			// Windows should have PATH
			assertThat(params.getEnv()).containsKey("PATH");
		}
		else {
			// Unix-like systems should have PATH and HOME
			assertThat(params.getEnv()).containsKey("PATH");
		}
	}

	@Test
	void testCustomEnvVarsOverrideDefaults() {
		// Get the current PATH
		String currentPath = System.getenv("PATH");

		AgentParameters params = AgentParameters.builder("gemini").addEnvVar("PATH", "/custom/path").build();

		assertThat(params.getEnv()).containsEntry("PATH", "/custom/path");
		assertThat(params.getEnv().get("PATH")).isNotEqualTo(currentPath);
	}

	@Test
	void testArgsListIsCopied() {
		List<String> originalArgs = new ArrayList<>(Arrays.asList("--arg1", "--arg2"));
		AgentParameters params = AgentParameters.builder("gemini").args(originalArgs).build();

		// Modifying the original list should not affect the params
		originalArgs.clear();
		assertThat(params.getArgs()).containsExactly("--arg1", "--arg2");
	}

	@Test
	void testEnvIsNotNull() {
		AgentParameters params = AgentParameters.builder("gemini").build();

		assertThat(params.getEnv()).isNotNull();
	}

	@Test
	void testComplexBuildChaining() {
		// Note: After calling args(varargs), cannot call arg() since args() uses Arrays.asList()
		AgentParameters params = AgentParameters.builder("gemini")
			.arg("--experimental-acp")
			.arg("--model")
			.arg("gemini-1.5-pro")
			.arg("--temperature")
			.arg("0.7")
			.addEnvVar("API_KEY", "key123")
			.env(Collections.singletonMap("DEBUG", "true"))
			.addEnvVar("LOG_LEVEL", "info")
			.build();

		assertThat(params.getCommand()).isEqualTo("gemini");
		assertThat(params.getArgs()).containsExactly("--experimental-acp", "--model", "gemini-1.5-pro", "--temperature",
				"0.7");
		assertThat(params.getEnv()).containsEntry("API_KEY", "key123")
			.containsEntry("DEBUG", "true")
			.containsEntry("LOG_LEVEL", "info");
	}

}
