-- ══════════════════════════════════════════════════════════════════
-- V3__retention_hierarchy.sql
-- Allows retention policies to bind at any level of the hierarchy.
-- Resolution order: document_type → category → product →
--                   product_line → segment → default
-- ══════════════════════════════════════════════════════════════════

ALTER TABLE ecm_admin.retention_policies
    ADD COLUMN segment_id      INTEGER REFERENCES ecm_admin.segments(id),
    ADD COLUMN product_line_id INTEGER REFERENCES ecm_admin.product_lines(id),
    ADD COLUMN priority        INTEGER NOT NULL DEFAULT 100;

-- Lower priority number wins. Specific bindings should have lower numbers.
-- e.g. category-level: 10, product_line-level: 50, segment-level: 80, default: 100
COMMENT ON COLUMN ecm_admin.retention_policies.priority IS
    'Lower = higher priority. Category-level ~10, segment-level ~80, global default 100.';

COMMENT ON COLUMN ecm_admin.retention_policies.segment_id IS
    'Bind this policy to a specific segment. NULL = applies to all segments.';

COMMENT ON COLUMN ecm_admin.retention_policies.product_line_id IS
    'Bind this policy to a specific product line. NULL = applies to all product lines.';