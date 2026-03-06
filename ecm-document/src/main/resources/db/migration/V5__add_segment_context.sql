-- ══════════════════════════════════════════════════════════════════
-- V5__add_segment_context.sql
-- Adds hierarchy context columns to ecm_core.documents.
-- segment_id and product_line_id are soft references to ecm_admin.
-- No DB-level FK constraints across schemas per platform convention.
-- ══════════════════════════════════════════════════════════════════

ALTER TABLE ecm_core.documents
    ADD COLUMN segment_id      INTEGER,  -- soft ref → ecm_admin.segments.id
    ADD COLUMN product_line_id INTEGER;  -- soft ref → ecm_admin.product_lines.id

CREATE INDEX idx_doc_segment      ON ecm_core.documents(segment_id);
CREATE INDEX idx_doc_product_line ON ecm_core.documents(product_line_id);

-- New uploads will use hierarchy path convention.
-- Existing documents retain their original blob_storage_path.
COMMENT ON COLUMN ecm_core.documents.segment_id IS
    'Soft ref to ecm_admin.segments.id — resolved at upload time from request context.';
COMMENT ON COLUMN ecm_core.documents.product_line_id IS
    'Soft ref to ecm_admin.product_lines.id — resolved at upload time from request context.';