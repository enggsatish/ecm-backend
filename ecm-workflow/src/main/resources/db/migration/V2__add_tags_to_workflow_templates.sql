ALTER TABLE ecm_workflow.workflow_templates
    ADD COLUMN IF NOT EXISTS tags TEXT[];
