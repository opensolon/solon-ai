/*
 * Copyright 2025-2025 the original author or authors.
 */

package com.agentclientprotocol.sdk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proactive tests to ensure scheduler best practices are followed in production code.
 *
 * <p>These tests scan the source code to detect violations of reactive best practices:
 * <ul>
 * <li>Using global schedulers like {@code Schedulers.boundedElastic()} in production code</li>
 * <li>Using non-daemon thread factories in executors</li>
 * <li>Blocking operations on the event loop</li>
 * </ul>
 *
 * <p>The goal is to fail fast if someone introduces code that could cause JVM shutdown issues
 * or thread pool pollution.
 *
 * @author Mark Pollack
 */
class SchedulerBestPracticesTest {

	private static final Path SOURCE_ROOT = Paths.get("src/main/java");

	/**
	 * Detects usage of global {@code Schedulers.boundedElastic()} in production code.
	 *
	 * <p>Global schedulers should be avoided because:
	 * <ul>
	 * <li>They use non-daemon threads that prevent JVM shutdown</li>
	 * <li>They persist beyond the lifecycle of individual sessions/clients</li>
	 * <li>They can cause "lingering thread" warnings in build tools</li>
	 * </ul>
	 *
	 * <p>Instead, use library-owned schedulers with daemon threads:
	 * <pre>{@code
	 * Scheduler scheduler = Schedulers.fromExecutorService(
	 *     Executors.newCachedThreadPool(r -> {
	 *         Thread t = new Thread(r, "acp-worker");
	 *         t.setDaemon(true);
	 *         return t;
	 *     }), "acp-worker");
	 * }</pre>
	 */
	@Test
	void noGlobalBoundedElasticInProductionCode() throws IOException {
		List<String> violations = new ArrayList<>();

		// Pattern to detect Schedulers.boundedElastic() usage in actual code (not comments)
		Pattern pattern = Pattern.compile("Schedulers\\.boundedElastic\\(\\)");

		try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
						String[] lines = content.split("\n");
						int lineNum = 0;
						for (String line : lines) {
							lineNum++;
							// Skip comment lines (single-line and continuation of multi-line)
							String trimmed = line.trim();
							if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
								continue;
							}
							Matcher matcher = pattern.matcher(line);
							if (matcher.find()) {
								violations.add(String.format("%s:%d - Uses Schedulers.boundedElastic()",
										relativePath(path), lineNum));
							}
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read: " + path, e);
					}
				});
		}

		assertThat(violations)
			.describedAs("Production code should not use global Schedulers.boundedElastic(). " +
					"Use library-owned schedulers with daemon threads instead. Violations found")
			.isEmpty();
	}

	/**
	 * Detects usage of global {@code Schedulers.parallel()} in production code.
	 *
	 * <p>Like boundedElastic(), parallel() uses global threads that persist beyond
	 * the application lifecycle and can prevent clean shutdown.
	 */
	@Test
	void noGlobalParallelInProductionCode() throws IOException {
		List<String> violations = new ArrayList<>();

		// Pattern to detect Schedulers.parallel() usage
		Pattern pattern = Pattern.compile("Schedulers\\.parallel\\(\\)");

		try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
						Matcher matcher = pattern.matcher(content);
						if (matcher.find()) {
							int lineNumber = getLineNumber(content, matcher.start());
							violations.add(String.format("%s:%d - Uses Schedulers.parallel()",
									relativePath(path), lineNumber));
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read: " + path, e);
					}
				});
		}

		assertThat(violations)
			.describedAs("Production code should not use global Schedulers.parallel(). " +
					"Use library-owned schedulers with daemon threads instead. Violations found")
			.isEmpty();
	}

	/**
	 * Detects creation of non-daemon thread executors without explicit daemon configuration.
	 *
	 * <p>This is a softer check that warns about executor creation patterns that might
	 * create non-daemon threads. The pattern looks for newSingleThreadExecutor() or
	 * newCachedThreadPool() without a ThreadFactory argument.
	 */
	@Test
	void executorCreationUsesDaemonThreads() throws IOException {
		List<String> violations = new ArrayList<>();

		// Pattern to detect Executors.newSingleThreadExecutor() without ThreadFactory
		// or Executors.newCachedThreadPool() without ThreadFactory
		// The negative lookahead (?!\s*\() ensures there's no open paren with args following
		Pattern singleThread = Pattern.compile(
				"Executors\\.newSingleThreadExecutor\\(\\s*\\)");
		Pattern cachedPool = Pattern.compile(
				"Executors\\.newCachedThreadPool\\(\\s*\\)");

		try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

						Matcher m1 = singleThread.matcher(content);
						while (m1.find()) {
							int lineNumber = getLineNumber(content, m1.start());
							violations.add(String.format(
									"%s:%d - newSingleThreadExecutor() without ThreadFactory (use daemon threads)",
									relativePath(path), lineNumber));
						}

						Matcher m2 = cachedPool.matcher(content);
						while (m2.find()) {
							int lineNumber = getLineNumber(content, m2.start());
							violations.add(String.format(
									"%s:%d - newCachedThreadPool() without ThreadFactory (use daemon threads)",
									relativePath(path), lineNumber));
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read: " + path, e);
					}
				});
		}

		assertThat(violations)
			.describedAs("Executor creation should use ThreadFactory with daemon threads. " +
					"Example: Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); " +
					"t.setDaemon(true); return t; }). Violations found")
			.isEmpty();
	}

	/**
	 * Verifies that AcpClient defines a library-owned scheduler with daemon threads.
	 *
	 * <p>This positive test ensures the SYNC_HANDLER_SCHEDULER is properly configured.
	 */
	@Test
	void acpClientHasDaemonScheduler() throws IOException {
		Path acpClientPath = SOURCE_ROOT.resolve(
				"com/agentclientprotocol/sdk/client/AcpClient.java");

		assertThat(acpClientPath)
			.describedAs("AcpClient.java should exist")
			.exists();

		String content = new String(Files.readAllBytes(acpClientPath), StandardCharsets.UTF_8);

		// Verify SYNC_HANDLER_SCHEDULER is defined
		assertThat(content)
			.describedAs("AcpClient should define SYNC_HANDLER_SCHEDULER")
			.contains("SYNC_HANDLER_SCHEDULER");

		// Verify daemon thread configuration
		assertThat(content)
			.describedAs("SYNC_HANDLER_SCHEDULER should use daemon threads")
			.contains("setDaemon(true)");

		// Verify it uses fromExecutorService (library-owned)
		assertThat(content)
			.describedAs("SYNC_HANDLER_SCHEDULER should use Schedulers.fromExecutorService")
			.contains("Schedulers.fromExecutorService");
	}

	/**
	 * Verifies that transport implementations use daemon threads.
	 */
	@Test
	void transportSchedulersUseDaemonThreads() throws IOException {
		// List of transport files that should use daemon threads
		List<String> transportFiles = Collections.unmodifiableList(Arrays.asList(
				"com/agentclientprotocol/sdk/client/transport/StdioAcpClientTransport.java",
				"com/agentclientprotocol/sdk/agent/transport/StdioAcpAgentTransport.java"
		));

		for (String transportFile : transportFiles) {
			Path path = SOURCE_ROOT.resolve(transportFile);
			if (Files.exists(path)) {
				String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

				// If it creates schedulers, it should use daemon threads
				if (content.contains("Schedulers.fromExecutorService")) {
					assertThat(content)
						.describedAs("%s should use daemon threads in scheduler creation", transportFile)
						.contains("setDaemon(true)");
				}
			}
		}
	}

	/**
	 * Detects time-based operators that use global schedulers implicitly.
	 *
	 * <p>Per BEST-PRACTICES-REACTIVE-SCHEDULERS.md, these operators use
	 * global schedulers internally and should use explicit scheduler variants:
	 * <ul>
	 * <li>Mono.delay(duration) - should use Mono.delay(duration, scheduler)</li>
	 * <li>Flux.interval(duration) - should use Flux.interval(duration, scheduler)</li>
	 * <li>.timeout(duration) - should use .timeout(duration, scheduler)</li>
	 * <li>.delayElements(duration) - should use .delayElements(duration, scheduler)</li>
	 * </ul>
	 */
	@Test
	void noImplicitSchedulerTimeOperators() throws IOException {
		List<String> violations = new ArrayList<>();

		// Patterns that use global schedulers implicitly
		// We look for patterns that DON'T have a second parameter (scheduler)
		// e.g., "Mono.delay(duration)" without ", scheduler)"

		// Pattern: Mono.delay(something) without a second param
		// This is tricky - we want Mono.delay(X) but not Mono.delay(X, scheduler)
		Pattern monoDelay = Pattern.compile("Mono\\.delay\\([^,)]+\\)(?!.*,)");

		// Pattern: Flux.interval(something) without a scheduler
		Pattern fluxInterval = Pattern.compile("Flux\\.interval\\([^,)]+\\)(?!.*,)");

		// Pattern: .timeout(something) that ends with ) not ,
		// We want to catch .timeout(duration) but allow .timeout(duration, scheduler)
		Pattern timeout = Pattern.compile("\\.timeout\\([^,)]+\\)");

		// Pattern: .delayElements(something) without scheduler
		Pattern delayElements = Pattern.compile("\\.delayElements\\([^,)]+\\)");

		try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
						String[] lines = content.split("\n");
						int lineNum = 0;
						for (String line : lines) {
							lineNum++;
							// Skip comment lines
							String trimmed = line.trim();
							if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
								continue;
							}

							if (monoDelay.matcher(line).find()) {
								violations.add(String.format(
										"%s:%d - Mono.delay() without explicit scheduler (use Mono.delay(duration, scheduler))",
										relativePath(path), lineNum));
							}
							if (fluxInterval.matcher(line).find()) {
								violations.add(String.format(
										"%s:%d - Flux.interval() without explicit scheduler (use Flux.interval(duration, scheduler))",
										relativePath(path), lineNum));
							}
							// For timeout and delayElements, need to be careful about multi-line
							if (timeout.matcher(line).find() && !line.contains("timeout(") ||
								(timeout.matcher(line).find() && !line.matches(".*\\.timeout\\([^,)]+\\s*,.*"))) {
								// Only flag if there's no comma before the closing paren
								Matcher m = Pattern.compile("\\.timeout\\(([^)]+)\\)").matcher(line);
								if (m.find() && !m.group(1).contains(",")) {
									violations.add(String.format(
											"%s:%d - .timeout() without explicit scheduler (use .timeout(duration, scheduler))",
											relativePath(path), lineNum));
								}
							}
							if (delayElements.matcher(line).find()) {
								Matcher m = Pattern.compile("\\.delayElements\\(([^)]+)\\)").matcher(line);
								if (m.find() && !m.group(1).contains(",")) {
									violations.add(String.format(
											"%s:%d - .delayElements() without explicit scheduler",
											relativePath(path), lineNum));
								}
							}
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read: " + path, e);
					}
				});
		}

		assertThat(violations)
			.describedAs("Time-based operators should use explicit scheduler to avoid global scheduler pollution. " +
					"Violations found")
			.isEmpty();
	}

	/**
	 * Detects usage of {@code Mono.fromFuture()} which often wraps JDK CompletableFutures
	 * that use ForkJoinPool.commonPool for completion.
	 *
	 * <p>Per BEST-PRACTICES-REACTIVE-SCHEDULERS.md, JDK APIs like {@code Process.onExit()},
	 * {@code HttpClient} async methods, etc. use ForkJoinPool.commonPool for callbacks.
	 * Wrapping these with Mono.fromFuture() doesn't change which thread runs the callbacks.
	 *
	 * <p>Alternatives:
	 * <ul>
	 * <li>Use blocking wait if in shutdown path: {@code process.waitFor()}</li>
	 * <li>Add {@code .publishOn(scheduler)} after fromFuture to control downstream thread</li>
	 * <li>Configure the underlying API with a custom executor (e.g., HttpClient.newBuilder().executor(...))</li>
	 * </ul>
	 *
	 * <p>Note: WebSocket transports are excluded because they configure HttpClient with a custom
	 * executor (acp-ws-client daemon threads), so their CompletableFutures don't use ForkJoinPool.
	 */
	@Test
	void noMonoFromFutureWithJdkCompletableFutures() throws IOException {
		List<String> violations = new ArrayList<>();

		// Pattern to detect Mono.fromFuture() usage
		Pattern pattern = Pattern.compile("Mono\\.fromFuture\\(");

		// Files that are known to use properly configured executors (not ForkJoinPool.commonPool)
		// WebSocket transports configure HttpClient with custom executor, so they're safe
		List<String> excludedFiles = Collections.unmodifiableList(Arrays.asList(
			"WebSocketAcpClientTransport.java",
			"WebSocketAcpAgentTransport.java"
		));

		try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.filter(p -> excludedFiles.stream().noneMatch(exc -> p.toString().endsWith(exc)))
				.forEach(path -> {
					try {
						String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
						String[] lines = content.split("\n");
						int lineNum = 0;
						for (String line : lines) {
							lineNum++;
							// Skip comment lines
							String trimmed = line.trim();
							if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
								continue;
							}
							Matcher matcher = pattern.matcher(line);
							if (matcher.find()) {
								violations.add(String.format(
										"%s:%d - Mono.fromFuture() may use ForkJoinPool.commonPool " +
										"(use blocking wait or add .publishOn(scheduler))",
										relativePath(path), lineNum));
							}
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Failed to read: " + path, e);
					}
				});
		}

		assertThat(violations)
			.describedAs("Mono.fromFuture() with JDK CompletableFutures uses ForkJoinPool.commonPool. " +
					"Use blocking wait in shutdown paths or add .publishOn(scheduler). Violations found")
			.isEmpty();
	}

	/**
	 * Gets the 1-based line number for a character position in the content.
	 */
	private int getLineNumber(String content, int charPosition) {
		int lineNumber = 1;
		for (int i = 0; i < charPosition && i < content.length(); i++) {
			if (content.charAt(i) == '\n') {
				lineNumber++;
			}
		}
		return lineNumber;
	}

	/**
	 * Returns the path relative to SOURCE_ROOT for cleaner output.
	 */
	private String relativePath(Path path) {
		return SOURCE_ROOT.relativize(path).toString();
	}

}
