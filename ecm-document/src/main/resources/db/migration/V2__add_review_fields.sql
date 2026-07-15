-- Pipeline state — data-driven pipeline visualization.
-- Each service appends its steps as JSONB array elements.
ALTER TABLE ecm_core.documents
    ADD COLUMN IF NOT EXISTS pipeline_state JSONB DEFAULT '[]'::jsonb;
