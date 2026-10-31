package io.paynexa.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSecretsTest {

    @Test
    void readsAndTrimsAMountedSecretFile(@TempDir Path dir) throws Exception {
        var f = Files.writeString(dir.resolve("key"), "-----BEGIN-----\nabc\n-----END-----\n");
        assertThat(FileSecrets.resolve(Map.of("K_FILE", f.toString()), "K")).isEqualTo("-----BEGIN-----\nabc\n-----END-----");
    }

    @Test
    void aDirectlySetValueWinsAndNothingSetMeansNull(@TempDir Path dir) throws Exception {
        var f = Files.writeString(dir.resolve("key"), "from-file");
        var env = new HashMap<String, String>();
        env.put("K", "direct");
        env.put("K_FILE", f.toString());
        assertThat(FileSecrets.resolve(env, "K")).isEqualTo("direct");
        assertThat(FileSecrets.resolve(Map.of(), "K")).isNull();
        assertThat(FileSecrets.resolve(Map.of("K_FILE", " "), "K")).isNull();
    }

    @Test
    void anUnreadableFileIsAnErrorNotASilentMissingSecret() {
        assertThatThrownBy(() -> FileSecrets.resolve(Map.of("K_FILE", "/definitely/not/here"), "K"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be read");
    }
}
