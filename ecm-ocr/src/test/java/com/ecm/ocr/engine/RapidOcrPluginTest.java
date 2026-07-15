package com.ecm.ocr.engine;

import com.ecm.ocr.service.OcrHttpClient;
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
class RapidOcrPluginTest {

    @Mock OcrHttpClient ocrHttpClient;
    @InjectMocks RapidOcrPlugin plugin;

    @Test
    @DisplayName("Engine ID is 'rapidocr'")
    void engineId() {
        assertThat(plugin.engineId()).isEqualTo("rapidocr");
    }

    @Test
    @DisplayName("Has only OCR capability")
    void capabilities() {
        assertThat(plugin.capabilities()).containsExactly(OcrEnginePlugin.Capability.OCR);
    }

    @Test
    @DisplayName("Extracts text via OcrHttpClient")
    void extractsText() {
        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withEngineConfig(Map.of());

        given(ocrHttpClient.recognizeImage(any(byte[].class), anyString(), any()))
                .willReturn("EXTRACTED TEXT FROM RAPIDOCR");

        OcrEngineResult result = plugin.process(new byte[]{1}, "image/jpeg", ctx);

        assertThat(result.text()).isEqualTo("EXTRACTED TEXT FROM RAPIDOCR");
        assertThat(result.detectedCategory()).isNull();
        assertThat(result.fields()).isEmpty();
    }

    @Test
    @DisplayName("Returns empty when no image bytes")
    void emptyWhenNoImage() {
        EngineContext ctx = EngineContext.initial(UUID.randomUUID(), "doc.jpg", null, null)
                .withEngineConfig(Map.of());

        OcrEngineResult result = plugin.process(null, "image/jpeg", ctx);
        assertThat(result.text()).isEmpty();
    }
}
