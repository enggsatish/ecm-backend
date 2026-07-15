package com.ecm.admin.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-shot startup diagnostic for the Spring AI MCP server auto-configuration.
 *
 * <p>Logs which MCP-related beans are present in the context immediately after
 * the application is ready. If the WebMvc transport provider or the router
 * function is missing, the Spring AI auto-configuration didn't fire and we need
 * to investigate the bean conditions.
 *
 * <p>Safe to delete once the MCP JSON-RPC migration is verified working.
 */
@Slf4j
@Component
public class McpStartupDiagnostic {

    private final ApplicationContext ctx;

    public McpStartupDiagnostic(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportMcpBeans() {
        log.info("=== MCP startup diagnostic ===");

        String[] allBeans = ctx.getBeanDefinitionNames();
        int mcpCount = 0;
        for (String name : allBeans) {
            if (name.toLowerCase().contains("mcp")
                    || name.toLowerCase().contains("routerfunction")
                    || name.toLowerCase().contains("toolcallback")) {
                try {
                    Object bean = ctx.getBean(name);
                    log.info("  bean [{}] = {}", name, bean.getClass().getName());
                    mcpCount++;
                } catch (Exception e) {
                    log.warn("  bean [{}] — failed to resolve: {}", name, e.getMessage());
                }
            }
        }
        log.info("=== Found {} MCP-related beans ===", mcpCount);

        // Explicit class-presence probes — if these throw ClassNotFoundException,
        // the Spring AI starter's @ConditionalOnClass will have skipped the autoconfig.
        probeClass("io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider");
        probeClass("io.modelcontextprotocol.spec.McpServerTransportProvider");
        probeClass("io.modelcontextprotocol.server.McpSyncServer");
        probeClass("org.springframework.ai.mcp.server.autoconfigure.McpWebMvcServerAutoConfiguration");
    }

    private void probeClass(String fqn) {
        try {
            Class<?> cls = Class.forName(fqn);
            log.info("  class-probe OK: {}", cls.getName());
        } catch (ClassNotFoundException e) {
            log.warn("  class-probe MISSING: {}", fqn);
        }
    }
}
