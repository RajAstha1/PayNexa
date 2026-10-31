package io.paynexa.payments.recon;

public record Finding(String check, Severity severity, String subject, String detail) {
    public enum Severity { CRITICAL, WARNING }
}
