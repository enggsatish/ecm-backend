package com.ecm.ocr.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OllamaClient — verifies connection test logic
 * and graceful failure handling (no Ollama server needed).
 */
class OllamaClientTest {

    OllamaClient client = new OllamaClient(new ObjectMapper());

    @Test
    @DisplayName("testConnection fails gracefully when Ollama not running")
    void testConnectionFailsWhenNotRunning() {
        ConnectionTestResult result = client.testConnection("http://localhost:19999", "glm-ocr");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).containsIgnoringCase("connect");
    }

    @Test
    @DisplayName("generate returns empty string when Ollama not reachable")
    void generateReturnsEmptyWhenUnreachable() {
        String result = client.generate("http://localhost:19999", "glm-ocr",
                "test prompt", new byte[]{1, 2, 3}, 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("generateText returns empty string when Ollama not reachable")
    void generateTextReturnsEmptyWhenUnreachable() {
        String result = client.generateText("http://localhost:19999", "llama3.2:3b",
                "test prompt", 5);

        assertThat(result).isEmpty();
    }
}
