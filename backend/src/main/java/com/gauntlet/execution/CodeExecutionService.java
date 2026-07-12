package com.gauntlet.execution;

import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Lightweight, single-tenant code execution runner.
 *
 * WHY THIS EXISTS INSTEAD OF JUDGE0:
 * Judge0 is built for running code from thousands of anonymous, adversarial
 * strangers (public onlin
 * e judges / contest platforms). That's why it needs
 * isolate's kernel-level cgroup v1 sandboxing. Gauntlet has exactly one user —
 * you, testing your own understanding — so that threat model doesn't apply.
 * Plain Docker containers with resource limits, no network, and a hard
 * timeout are a correctly-sized amount of isolation for this use case.
 *
 * WHAT THIS IS NOT:
 * Not a multi-tenant, internet-facing judge. If Gauntlet ever needs to run
 * code from untrusted third parties at scale, revisit Judge0 or a proper
 * microVM sandbox (Firecracker/gVisor) at that point — don't build that now.
 */
@Service
public class CodeExecutionService {

    private static final long WALL_CLOCK_TIMEOUT_SECONDS = 10; // hard ceiling, includes container startup
    private static final int INNER_TIMEOUT_SECONDS = 5;        // enforced by `timeout` inside the container
    private static final String MEMORY_LIMIT = "256m";
    private static final String CPU_LIMIT = "0.5";
    private static final int PIDS_LIMIT = 64;

    public enum Language {
        PYTHON("python:3.11-slim", "Main.py",
                "cp /sandbox/src/Main.py . && timeout %d python3 Main.py"),
        JAVASCRIPT("node:20-slim", "main.js",
                "cp /sandbox/src/main.js . && timeout %d node main.js"),
        JAVA("eclipse-temurin:21-jdk", "Main.java",
                "cp /sandbox/src/Main.java . && javac Main.java && timeout %d java Main"),
        CPP("gcc:13", "main.cpp",
                "cp /sandbox/src/main.cpp . && g++ -O2 -o prog main.cpp && timeout %d ./prog");

        final String image;
        final String fileName;
        final String commandTemplate;

        Language(String image, String fileName, String commandTemplate) {
            this.image = image;
            this.fileName = fileName;
            this.commandTemplate = commandTemplate;
        }
    }

    public record ExecutionResult(
            int exitCode,
            String stdout,
            String stderr,
            boolean timedOut,
            boolean likelyOutOfMemory
    ) {}

    /**
     * Runs user-submitted source code against the given stdin input, inside
     * an isolated, network-disabled, resource-capped Docker container.
     */
    public ExecutionResult run(Language language, String sourceCode, String stdin) throws IOException {
        String containerName = "gauntlet-run-" + UUID.randomUUID();
        Path hostSrcDir = Files.createTempDirectory("gauntlet-src-");

        try {
            Path sourceFile = hostSrcDir.resolve(language.fileName);
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            String innerCommand = String.format(language.commandTemplate, INNER_TIMEOUT_SECONDS);

            List<String> dockerCommand = List.of(
                    "docker", "run", "--rm", "-i",
                    "--name", containerName,
                    "--network", "none",
                    "--memory", MEMORY_LIMIT,
                    "--cpus", CPU_LIMIT,
                    "--pids-limit", String.valueOf(PIDS_LIMIT),
                    "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges",
                    "--tmpfs", "/sandbox/work:rw,size=64m,mode=1777",
                    "-v", hostSrcDir.toAbsolutePath() + ":/sandbox/src:ro",
                    "-w", "/sandbox/work",
                    "--user", "1000:1000",
                    language.image,
                    "sh", "-c", innerCommand
            );

            ProcessBuilder pb = new ProcessBuilder(dockerCommand);
            Process process = pb.start();

            // Start draining stdout/stderr on separate threads BEFORE writing stdin.
            // If we wrote all of stdin first, a program that emits output while we
            // are still feeding it a large input would fill its stdout pipe buffer,
            // block, and deadlock us (we'd block on write, it blocks on write) until
            // the wall-clock timeout fires.
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<String> stdoutFuture = pool.submit(() -> readStream(process.getInputStream()));
            Future<String> stderrFuture = pool.submit(() -> readStream(process.getErrorStream()));

            // Feed stdin, then close it so the program sees EOF. If the process has
            // already exited (e.g. crashed on startup), the pipe is broken — that's
            // expected, not an error, so swallow it and let exit code reporting stand.
            try (OutputStream stdinStream = process.getOutputStream()) {
                stdinStream.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Broken pipe: process already gone. Nothing to feed.
            }

            boolean finished = process.waitFor(WALL_CLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean timedOut = !finished;

            if (timedOut) {
                // The inner `timeout` command should have already killed the
                // user's process; this is a safety net in case the container
                // itself hangs (e.g. a fork bomb outrunning pids-limit).
                killContainer(containerName);
                process.destroyForcibly();
            }

            int exitCode = timedOut ? -1 : process.exitValue();
            String stdout = safeGet(stdoutFuture);
            String stderr = safeGet(stderrFuture);
            pool.shutdownNow();

            // Exit code 137 = SIGKILL, commonly the OOM killer via cgroup memory limit.
            boolean likelyOom = exitCode == 137;

            return new ExecutionResult(exitCode, stdout, stderr, timedOut, likelyOom);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Execution interrupted", e);
        } finally {
            deleteRecursively(hostSrcDir);
        }
    }

    private void killContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "kill", containerName).start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ignored) {
            // Container may have already exited on its own; nothing further to do.
        }
    }

    private String readStream(InputStream in) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private String safeGet(Future<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}