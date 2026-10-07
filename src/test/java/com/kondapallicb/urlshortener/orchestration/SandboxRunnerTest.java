package com.kondapallicb.urlshortener.orchestration;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class SandboxRunnerTest {
    @TempDir Path directory;

    @Test void parentArtifactReaderRejectsSymlinkEscapes() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path outside = directory.resolve("outside.txt");
        Files.writeString(outside, "synthetic-private-data");
        Path link = workspace.resolve("report.xml");
        Files.createSymbolicLink(link, outside);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> WorkspaceExecutionService.checkOutput(workspace, link))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("Symlink");
    }

    @Test void osSandboxDeniesHostFilesWritesAndNetworkAndStripsCredentials() throws Exception {
        assumeFalse(Boolean.getBoolean("agent.execution.child"));
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path cache = Files.createDirectories(directory.resolve("cache"));
        Path outside = directory.resolve("outside.txt");
        Files.writeString(outside, "synthetic-private-data");
        Path java = workspace.resolve("SandboxProbe.java");
        Files.writeString(java, """
                import java.nio.file.*;
                import java.net.*;
                public class SandboxProbe {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of("allowed.txt"), "allowed");
                        try { Files.readString(Path.of(args[0])); throw new AssertionError("Host read escaped sandbox"); }
                        catch (java.io.IOException denied) { }
                        try { Files.writeString(Path.of(args[0]), "overwritten"); throw new AssertionError("Host write escaped sandbox"); }
                        catch (java.io.IOException denied) { }
                        try (Socket socket = new Socket()) {
                            try { socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), 500);
                                throw new AssertionError("Network escaped sandbox"); }
                            catch (java.io.IOException denied) { }
                        }
                        if (System.getenv("WORKFLOW_OPERATOR_TOKEN") != null) throw new AssertionError("Credential inherited");
                    }
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, java.toString())).isZero();
        Path log = directory.resolve("probe.log");
        var launch = new SandboxRunner().maven(workspace, log, "mvn", cache);
        assertThat(launch.command()).contains("--offline");
        assertThat(launch.environment()).doesNotContainKeys("WORKFLOW_OPERATOR_TOKEN", "AWS_SECRET_ACCESS_KEY");
        try (var listener = new ServerSocket(0)) {
            var builder = new ProcessBuilder("/usr/bin/sandbox-exec", "-f", workspace.resolve("execution.sb").toString(),
                    Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", workspace.toString(),
                    "SandboxProbe", outside.toString(), Integer.toString(listener.getLocalPort()))
                    .directory(workspace.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().clear(); builder.environment().putAll(launch.environment());
            var process = builder.start();
            try {
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).as(Files.readString(log)).isZero();
            } finally { process.destroyForcibly(); }
        }
        assertThat(Files.readString(workspace.resolve("allowed.txt"))).isEqualTo("allowed");
        assertThat(Files.readString(outside)).isEqualTo("synthetic-private-data");
    }
}
