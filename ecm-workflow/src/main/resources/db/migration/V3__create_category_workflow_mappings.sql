-- Category → Workflow Template mappings.
-- Determines which workflow to trigger when a document of a given category is uploaded.
-- One workflow per category (UNIQUE constraint). Multiple categories can map to the same template.
CREATE TABLE IF NOT EXISTS ecm_workflow.category_workflow_mappings (
    id           SERIAL       PRIMARY KEY,
    category_id  INTEGER      NOT NULL,
    template_id  INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_templates(id) ON DELETE CASCADE,
    is_active    BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(200),
    CONSTRAINT uq_category_workflow_mapping UNIQUE (category_id)
);

COMMENT ON TABLE  ecm_workflow.category_workflow_mappings IS 'Maps document categories to workflow templates for auto-trigger on upload';
COMMENT ON COLUMN ecm_workflow.category_workflow_mappings.category_id IS 'FK to ecm_admin.document_categories.id (soft ref, no cross-schema FK)';
COMMENT ON COLUMN ecm_workflow.category_workflow_mappings.template_id IS 'FK to workflow_templates.id — must be PUBLISHED';
