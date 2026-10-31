package io.paynexa.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Secrets arrive as mounted files (Docker/Kubernetes secrets) rather than environment variables, which leak more
 * easily through process listings, crash dumps and child processes. For a name like {@code PAYNEXA_JWT_PUBLIC_KEY}, the
 * value of {@code PAYNEXA_JWT_PUBLIC_KEY_FILE} is read as a path. A directly set variable wins; a path that is set but
 * unreadable is an error, never a silent fallback to "no secret".
 */
public final class FileSecrets {

    private FileSecrets() {}

    public static String resolve(Map<String, String> env, String name) {
        var direct = env.get(name);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        var file = env.get(name + "_FILE");
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            // never include file contents; the path is configuration, not a secret
            throw new IllegalStateException(name + "_FILE is set but " + file + " could not be read", e);
        }
    }
}
