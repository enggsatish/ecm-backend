package com.ecm.ocr.pipeline;

import com.ecm.ocr.engine.EngineContext.FewShotExample;
import com.ecm.ocr.engine.OcrEngineResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class GlmTrainingServiceTest {

    @Mock JdbcTemplate jdbc;
    ObjectMapper objectMapper = new ObjectMapper();
    GlmTrainingService service;

    @BeforeEach
    void setUp() {
        service = new GlmTrainingService(jdbc, objectMapper);
    }

    @Nested
    @DisplayName("saveExample()")
    class SaveExample {

        @Test
        @DisplayName("Saves Azure result as training example")
        void savesAzureResult() {
            OcrEngineResult result = new OcrEngineResult("text", Map.of("full_name", "John", "dob", "1990"),
                    "IDENTITY", BigDecimal.valueOf(95), List.of(), "azure", "prebuilt-idDocument");

            // Mock useNewTable() check — simulate new table exists
            given(jdbc.queryForObject(contains("training_examples LIMIT 0"), eq(Integer.class)))
                    .willReturn(1);
            // Mock duplicate check (uses document hash)
            given(jdbc.queryForObject(contains("document_hash"), eq(Integer.class), anyString()))
                    .willReturn(0);
            // Mock count check (uses category_code)
            given(jdbc.queryForObject(contains("category_code"), eq(Integer.class), eq("IDENTITY")))
                    .willReturn(0);

            service.saveExample("IDENTITY", result, new byte[]{1, 2, 3}, "AZURE");

            verify(jdbc).update(contains("INSERT INTO ecm_admin.training_examples"),
                    eq("IDENTITY"), eq("AZURE"), anyString(), anyString(),
                    isNull(), eq("system:azure"));
        }

        @Test
        @DisplayName("Skips when no fields")
        void skipsNoFields() {
            OcrEngineResult result = OcrEngineResult.textOnly("text", "azure");

            service.saveExample("IDENTITY", result, new byte[]{1}, "AZURE");

            verify(jdbc, never()).update(contains("INSERT"), any(Object[].class));
        }

        @Test
        @DisplayName("Skips when no category")
        void skipsNoCategory() {
            OcrEngineResult result = new OcrEngineResult("text", Map.of("name", "John"),
                    null, null, List.of(), "azure", "azure");

            service.saveExample(null, result, new byte[]{1}, "AZURE");

            verify(jdbc, never()).update(contains("INSERT"), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("loadExamples()")
    class LoadExamples {

        @Test
        @DisplayName("Merges multiple rows into one combined example per category")
        void mergesExamples() {
            // Mock useNewTable() — simulate new table exists
            given(jdbc.queryForObject(contains("training_examples LIMIT 0"), eq(Integer.class)))
                    .willReturn(1);
            // First query: ACTIVE/VERIFIED — return empty to trigger CANDIDATE fallback
            given(jdbc.queryForList(contains("ACTIVE"), eq("IDENTITY")))
                    .willReturn(List.of());
            // CANDIDATE fallback
            given(jdbc.queryForList(contains("CANDIDATE"), eq("IDENTITY")))
                    .willReturn(List.of(
                            Map.of("category_code", "IDENTITY", "source", "REGION",
                                    "expected_fields", "{\"fields\":{\"last_name\":\"ANDERSON\"}}"),
                            Map.of("category_code", "IDENTITY", "source", "AZURE",
                                    "expected_fields", "{\"fields\":{\"last_name\":\"ANDERSON,\",\"first_name\":\"Erin\",\"dob\":\"1985\"}}")
                    ));

            List<FewShotExample> examples = service.loadExamples("IDENTITY", 2);

            assertThat(examples).hasSize(1);
            // REGION fields should take priority (loaded first, putIfAbsent)
            assertThat(examples.get(0).expectedJson()).contains("\"last_name\":\"ANDERSON\"");
            assertThat(examples.get(0).expectedJson()).doesNotContain("ANDERSON,");
        }

        @Test
        @DisplayName("Returns empty when table doesn't exist")
        void emptyWhenNoTable() {
            // Mock useNewTable() — simulate table doesn't exist
            given(jdbc.queryForObject(contains("training_examples LIMIT 0"), eq(Integer.class)))
                    .willThrow(new org.springframework.jdbc.BadSqlGrammarException("", "", null));
            // Legacy table also fails
            given(jdbc.queryForList(anyString(), anyString()))
                    .willThrow(new org.springframework.jdbc.BadSqlGrammarException("", "", null));

            List<FewShotExample> examples = service.loadExamples("IDENTITY", 2);
            assertThat(examples).isEmpty();
        }
    }
}
