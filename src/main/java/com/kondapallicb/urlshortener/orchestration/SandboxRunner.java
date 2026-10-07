package com.kondapallicb.urlshortener.orchestration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SandboxRunner {
    public record Launch(List<String> command, Map<String, String> environment, String isolation) { }

    public Launch maven(Path workspace, Path log, String executable, Path cache) throws IOException {
        if (!System.getProperty("os.name").startsWith("Mac")) {
            throw new IOException("No supported OS sandbox on this host; configure a macOS worker. Unsandboxed fallback is forbidden");
        }
        Path maven = resolve(executable);
        if (!Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) throw new IOException("OS sandbox unavailable");
        Path home = workspace.resolve(".sandbox-home");
        Path temporary = workspace.resolve(".sandbox-tmp");
        Files.createDirectories(home);
        Files.createDirectories(temporary);
        Path settings = workspace.resolve("sandbox-settings.xml");
        Files.writeString(settings, "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"/>");
        Path profile = workspace.resolve("execution.sb");
        Files.writeString(profile, profile(workspace, log, cache));
        var command = new ArrayList<>(List.of("/usr/bin/sandbox-exec", "-f", profile.toString(),
                maven.toString(), "--offline", "--batch-mode", "--no-transfer-progress", "--show-version", "--settings", settings.toString(),
                "clean", "verify", "-Dagent.execution.child=true", "-Dmaven.repo.local=" + cache,
                "-Daether.syncContext.named.factory=rwlock-local", "-Daether.system.named.factory=rwlock-local",
                "-Dapp.storage.directory=" + workspace.resolve(".validation-data"),
                "-Duser.home=" + home, "-Djava.io.tmpdir=" + temporary));
        return new Launch(List.copyOf(command), Map.of("JAVA_HOME", System.getProperty("java.home"),
                "HOME", home.toString(), "TMPDIR", temporary.toString(),
                "PATH", "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin", "LANG", "en_US.UTF-8",
                "MAVEN_OPTS", "-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m",
                "JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=\"" + temporary + "\" -Duser.home=\"" + home + "\""),
                "MACOS_SEATBELT_OFFLINE");
    }

    String profile(Path workspace, Path log, Path cache) throws IOException {
        return """
                (version 1)
                (deny default)
                (allow process*)
                (allow sysctl-read)
                (allow mach-lookup)
                (allow file-read-metadata)
                (allow file-map-executable)
                (allow file-read*
                    (literal "/")
                    (subpath "/System") (subpath "/usr") (subpath "/bin") (subpath "/sbin")
                    (subpath "/opt/homebrew") (subpath "/usr/local") (subpath "/Library/Java")
                    (subpath "/private/var/db/timezone") (subpath "/dev")
                    (subpath "/private/var/db/dyld")
                    (subpath %s) (subpath %s) (subpath %s))
                (allow file-write* (subpath %s) (literal %s) (literal "/dev/null"))
                (deny network*)
                """.formatted(quote(workspace.toRealPath()), quote(cache.toRealPath()),
                        quote(Path.of(System.getProperty("java.home")).toRealPath()), quote(workspace.toRealPath()), quote(log.toAbsolutePath()));
    }

    private String quote(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private Path resolve(String executable) throws IOException {
        Path path = Path.of(executable);
        if (path.isAbsolute()) {
            if (!Files.isExecutable(path)) throw new IOException("Maven executable unavailable: " + path);
            return path.toRealPath();
        }
        if (path.getNameCount() != 1) throw new IOException("Maven executable must be absolute or a command name");
        for (String entry : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(entry).resolve(path);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) return candidate.toRealPath();
        }
        throw new IOException("Maven executable unavailable: " + executable);
    }
}
