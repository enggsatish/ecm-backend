package com.ecm.ocr.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AzureOcrPluginTest {

    @Mock AzureDocumentAiEngine azureEngine;
    @InjectMocks AzureOcrPlugin plugin;

    @Test
    @DisplayName("Engine ID is 'azure'")
    void engineId() {
        assertThat(plugin.engineId()).isEqualTo("azure");
    }

    @Test
    @DisplayName("Has OCR, CLASSIFY, and EXTRACT_FIELDS capabilities")
    void capabilities() {
        assertThat(plugin.capabilities()).containsExactlyInAnyOrder(
                OcrEnginePlugin.Capability.OCR,
                OcrEnginePlugin.Capability.CLASSIFY,
                OcrEnginePlugin.Capability.EXTRACT_FIELDS);
    }

    @Test
    @DisplayName("Uses category-specific model when category is known")
    void usesCategoryModel() {
        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "dl.jpg", "IDENTITY", 3)
                .withEngineConfig(Map.of());

        given(azureEngine.analyze(any(byte[].class), anyString(), eq("IDENTITY"), any()))
                .willReturn(new AzureDocumentAiEngine.AzureOcrResult("text",
                        Map.of("first_name", "Erin", "last_name", "Anderson")));

        OcrEngineResult result = plugin.process(new byte[]{1}, "image/jpeg", ctx);

        assertThat(result.fields()).containsEntry("first_name", "Erin");
        verify(azureEngine).analyze(any(), anyString(), eq("IDENTITY"), any());
    }

    @Test
    @DisplayName("Falls back to LAYOUT when no category")
    void fallsBackToLayout() {
        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withEngineConfig(Map.of());

        given(azureEngine.analyze(any(byte[].class), anyString(), eq("LAYOUT"), any()))
                .willReturn(new AzureDocumentAiEngine.AzureOcrResult("text", Map.of()));

        plugin.process(new byte[]{1}, "image/jpeg", ctx);

        verify(azureEngine).analyze(any(), anyString(), eq("LAYOUT"), any());
    }

    @Test
    @DisplayName("Returns empty when no image")
    void emptyWhenNoImage() {
        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withEngineConfig(Map.of());

        OcrEngineResult result = plugin.process(null, "image/jpeg", ctx);
        assertThat(result.text()).isEmpty();
    }
}
