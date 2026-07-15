package com.ecm.admin.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers ECM's MCP tools with Spring AI's server starter.
 *
 * <p>Spring AI 1.0.x discovers {@code @Tool}-annotated methods through
 * {@link ToolCallbackProvider} beans. At startup, the auto-configuration collects
 * all providers, de-duplicates by tool name, and exposes them through the MCP
 * JSON-RPC endpoints configured in {@code application.yml}
 * ({@code /mcp/sse} for the SSE stream, {@code /mcp/message} for POSTs).
 *
 * <p>If ECM ever adds a second tool class (e.g., admin-only tools behind an
 * additional security check), add another {@code @Bean ToolCallbackProvider} here —
 * the auto-config will merge them automatically.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider ecmMcpToolCallbacks(EcmMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
