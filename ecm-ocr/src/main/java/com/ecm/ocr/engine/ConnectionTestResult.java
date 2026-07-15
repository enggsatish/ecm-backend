package com.ecm.ocr.engine;

/**
 * Result of an engine connection test, shown in the admin UI.
 *
 * @param success   true if the engine is reachable and configured correctly
 * @param message   human-readable status message
 * @param latencyMs round-trip time in milliseconds (0 if failed)
 */
public record ConnectionTestResult(boolean success, String message, long latencyMs) {

    public static ConnectionTestResult ok(String message, long latencyMs) {
        return new ConnectionTestResult(true, message, latencyMs);
    }

    public static ConnectionTestResult fail(String message) {
        return new ConnectionTestResult(false, message, 0);
    }
}
