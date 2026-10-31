package io.paynexa.platform;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Copies *_FILE secrets into the property names the services bind, before any bean is created. */
public final class FileSecretsPostProcessor implements EnvironmentPostProcessor {

    private static final List<String> NAMES = List.of(
            "PAYNEXA_JWT_PUBLIC_KEY", "PAYNEXA_JWT_PRIVATE_KEY", "PAYNEXA_BOOTSTRAP_ADMIN_PASSWORD",
            "PAYMENTS_DB_PASSWORD", "PAYMENTS_DB_MIGRATOR_PASSWORD", "IDENTITY_DB_PASSWORD", "IDENTITY_DB_MIGRATOR_PASSWORD",
            "NOTIFICATION_DB_PASSWORD", "NOTIFICATION_DB_MIGRATOR_PASSWORD");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var env = new HashMap<String, String>();
        for (var name : NAMES) {
            env.put(name, environment.getProperty(name));
            env.put(name + "_FILE", environment.getProperty(name + "_FILE"));
        }
        var resolved = new HashMap<String, Object>();
        for (var name : NAMES) {
            var value = FileSecrets.resolve(env, name);
            if (value != null) {
                resolved.put(name, value);
            }
        }
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("paynexa-file-secrets", Map.copyOf(resolved)));
        }
    }
}
