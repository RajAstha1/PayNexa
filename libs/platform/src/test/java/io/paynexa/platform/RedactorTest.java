package io.paynexa.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @Test
    void masksBearerTokensAndJwts() {
        var jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl";
        assertThat(Redactor.redact("Authorization: Bearer " + jwt)).doesNotContain(jwt).contains("[REDACTED");
        assertThat(Redactor.redact("token was " + jwt + " ok")).doesNotContain(jwt);
    }

    @Test
    void masksKeyedSecretsInJsonAndFormEncoding() {
        assertThat(Redactor.redact("{\"email\":\"a@b.co\",\"password\":\"hunter2!\"}")).doesNotContain("hunter2");
        assertThat(Redactor.redact("grant=x&password=hunter2&refresh_token=abc123")).doesNotContain("hunter2").doesNotContain("abc123");
        assertThat(Redactor.redact("api_key: sk_live_123")).doesNotContain("sk_live_123");
    }

    @Test
    void masksEmailsKeepingFirstLetterAndDomainForDebugging() {
        assertThat(Redactor.redact("user alice.smith@example.com failed")).isEqualTo("user a***@example.com failed");
    }

    @Test
    void masksCardLikeNumbers() {
        assertThat(Redactor.redact("pan 4111 1111 1111 1111 seen")).doesNotContain("4111").contains("[REDACTED_NUMBER]");
        assertThat(Redactor.redact("order 12345")).isEqualTo("order 12345");
    }

    @Test
    void forLogRemovesLineBreaksSoInputCannotForgeLogLines() {
        assertThat(Redactor.forLog("ok\nINFO forged line\r\n")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void nullSafe() {
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.forLog(null)).isNull();
    }
}
