-- ============================================================================
-- V4: Drop legacy regex-based OCR templates table.
--
-- Replaced by the Enterprise OCR Pipeline (V3):
--   - ecm_admin.extraction_templates  → what fields to extract per category
--   - ecm_admin.field_mappings        → raw → canonical name normalization
--   - ecm_admin.training_examples     → few-shot learning examples (LLM)
--
-- The old regex approach (FieldExtractorService, TemplateLearningService)
-- was removed from ecm-ocr on 2026-04-05. This table has had no reader
-- since then, only the admin UI was writing to it.
-- ============================================================================

DROP TABLE IF EXISTS ecm_admin.ocr_templates CASCADE;
