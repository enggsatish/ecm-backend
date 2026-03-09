-- V4__seed_workflow_templates.sql
--
-- Seeds the three WorkflowTemplate rows required at runtime.
--
-- Why Flyway and not init.sql?
--   init.sql runs once during Docker first-boot and is not version-controlled
--   for incremental changes. Any service restart that skips init.sql (e.g. when
--   the volume already exists) will leave workflow_templates empty.
--   Putting seed data in a Flyway migration guarantees it is applied on every
--   fresh schema and is idempotent via ON CONFLICT DO NOTHING.
--
-- Templates seeded:
--
--   1. unlinked-document-triage  (PUBLISHED, not default)
--      Used when a document is uploaded without a partyExternalId.
--      DocumentUploadedListener calls resolveUnlinked() which looks for this
--      processKey first. Without this row, the listener throws
--      IllegalStateException and the message is dead-lettered on every upload.
--      The BPMN file (unlinked-document-triage.bpmn20.xml) is already deployed
--      on classpath — Flowable loads it at startup. This row is the ECM
--      metadata record that maps the processKey to SLA config and routing.
--
--   2. document-single-review    (PUBLISHED, is_default = TRUE)
--      System catch-all for any document that has a party but no specific
--      category/product mapping. resolveUnlinked() falls back to this when
--      unlinked-document-triage is not found. resolve() also falls back to it.
--      Without this row, any upload triggers the same IllegalStateException.
--
--   3. document-dual-review      (PUBLISHED, not default)
--      Two-stage review process for high-value or regulated document types.
--      Provided so admins can map categories/products to it via
--      workflow_template_mappings without needing to create it from scratch.
--
-- ON CONFLICT DO NOTHING: safe to re-run; will not overwrite manual changes
-- made via the admin UI after initial seed.

SET search_path TO ecm_workflow;

INSERT INTO workflow_templates
(name, description, process_key, dsl_definition, bpmn_source, status, is_default, sla_hours, warning_threshold_pct)
VALUES
    -- ── 1. Unlinked document triage ────────────────────────────────────────
    (
        'Unlinked Document Triage',
        'Backoffice task to link an uploaded document to a party when no partyExternalId was provided at upload time.',
        'unlinked-document-triage',
        '{"processKey":"unlinked-document-triage","name":"Unlinked Document Triage","triggerType":"DOCUMENT_UPLOAD","steps":[],"variables":{"reviewerGroup":"ECM_BACKOFFICE"},"endStates":[]}'::jsonb,
        'DSL',
        'PUBLISHED',
        FALSE,
        24,
        80
    ),

    -- ── 2. Default single review (system catch-all) ────────────────────────
    (
        'Default Single Review',
        'Default workflow template for general document review. Used as the system catch-all when no specific category or product mapping exists.',
        'document-single-review',
        '{"processKey":"document-single-review","name":"Default Single Review","triggerType":"MANUAL","steps":[],"variables":{},"endStates":[]}'::jsonb,
        'DSL',
        'PUBLISHED',
        TRUE,    -- is_default: catch-all for resolve() and resolveUnlinked() fallback
        48,
        80
    ),

    -- ── 3. Dual review (high-value / regulated documents) ──────────────────
    (
        'Dual Review',
        'Two-stage review for mortgage applications and other regulated document types. Map specific categories or products to this template via workflow_template_mappings.',
        'document-dual-review',
        '{"processKey":"document-dual-review","name":"Dual Review","triggerType":"DOCUMENT_UPLOAD","steps":[],"variables":{"reviewerGroup":"ECM_REVIEWER","backofficeGroup":"ECM_BACKOFFICE"},"endStates":[]}'::jsonb,
        'DSL',
        'PUBLISHED',
        FALSE,
        24,
        75
    )

    ON CONFLICT (process_key) DO NOTHING;