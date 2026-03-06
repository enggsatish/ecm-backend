-- ═══════════════════════════════════════════════════════════════════════════════
-- V1__workflow_schema_baseline.sql
-- ECM Workflow — Flyway baseline for ecm_workflow schema.
--
-- WHY THIS FILE EXISTS:
--   The ecm_workflow schema is created by infrastructure/sql/init.sql.
--   Flyway requires at least one migration file to establish its
--   schema_history table in the ecm_workflow schema.
--   Without this file, Flyway with `baseline-on-migrate: true` and an empty
--   migrations folder will set baseline_version=1 correctly BUT if the
--   flyway_schema_history table can't be created (permissions, missing schema)
--   the service fails to start.
--
--   This migration is FULLY IDEMPOTENT — all statements use IF NOT EXISTS.
--   It is safe to run on a database that already has the tables (from init.sql).
--
-- SCHEMA: ecm_workflow (set via spring.flyway.schemas)
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Ensure schema exists (idempotent guard)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS ecm_workflow;

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Groups
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_groups (
                                                            id          SERIAL       PRIMARY KEY,
                                                            name        VARCHAR(200) NOT NULL,
    description VARCHAR(500),
    group_key   VARCHAR(100) NOT NULL UNIQUE,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Group Members
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_group_members (
                                                                   id        SERIAL  PRIMARY KEY,
                                                                   group_id  INTEGER NOT NULL REFERENCES ecm_workflow.workflow_groups(id) ON DELETE CASCADE,
    user_id   INTEGER NOT NULL,
    added_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wgm UNIQUE (group_id, user_id)
    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Definition Configs
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_definition_configs (
                                                                        id                  SERIAL       PRIMARY KEY,
                                                                        name                VARCHAR(200) NOT NULL,
    description         VARCHAR(500),
    process_key         VARCHAR(100) NOT NULL,
    assigned_role       VARCHAR(100) NOT NULL DEFAULT 'ECM_BACKOFFICE',
    assigned_group_id   INTEGER      REFERENCES ecm_workflow.workflow_groups(id),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    sla_hours           INTEGER,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Category → Workflow Mappings
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.category_workflow_mappings (
                                                                       id                     SERIAL  PRIMARY KEY,
                                                                       category_id            INTEGER NOT NULL UNIQUE,
                                                                       workflow_definition_id INTEGER NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
    is_active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Templates (low-code DSL engine)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_templates (
                                                               id                      SERIAL       PRIMARY KEY,
                                                               name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    process_key             VARCHAR(200) UNIQUE,
    dsl_definition          JSONB        NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    version                 INTEGER      NOT NULL DEFAULT 1,
    is_default              BOOLEAN      NOT NULL DEFAULT FALSE,
    sla_hours               INTEGER      NOT NULL DEFAULT 48,
    warning_threshold_pct   INTEGER      NOT NULL DEFAULT 80,
    escalation_hours        INTEGER,
    escalation_group_key    VARCHAR(100),
    flowable_deployment_id  VARCHAR(200),
    flowable_process_def_id VARCHAR(200),
    created_by              VARCHAR(200),
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
    );

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Template Mappings (product + category → template)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_template_mappings (
                                                                       id          SERIAL   PRIMARY KEY,
                                                                       template_id INTEGER  NOT NULL REFERENCES ecm_workflow.workflow_templates(id),
    product_id  INTEGER,
    category_id INTEGER  NOT NULL,
    priority    INTEGER  NOT NULL DEFAULT 100,
    is_active   BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_template_mapping UNIQUE (product_id, category_id)
    );

CREATE INDEX IF NOT EXISTS idx_wtm_category ON ecm_workflow.workflow_template_mappings(category_id);
CREATE INDEX IF NOT EXISTS idx_wtm_product  ON ecm_workflow.workflow_template_mappings(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Instance Records
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_instance_records (
                                                                      id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    process_instance_id    VARCHAR(100) NOT NULL UNIQUE,
    document_id            UUID         NOT NULL,
    document_name          VARCHAR(500),
    category_id            INTEGER,
    workflow_definition_id INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
    status                 VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    trigger_type           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    started_by_subject     VARCHAR(255) NOT NULL,
    started_by_email       VARCHAR(255),
    template_id            INTEGER,
    completed_at           TIMESTAMPTZ,
    final_comment          TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_wir_document_id ON ecm_workflow.workflow_instance_records(document_id);
CREATE INDEX IF NOT EXISTS idx_wir_status      ON ecm_workflow.workflow_instance_records(status);
CREATE INDEX IF NOT EXISTS idx_wir_created_at  ON ecm_workflow.workflow_instance_records(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow SLA Tracking
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ecm_workflow.workflow_sla_tracking (
                                                                  id                   SERIAL    PRIMARY KEY,
                                                                  workflow_instance_id UUID      NOT NULL UNIQUE
                                                                  REFERENCES ecm_workflow.workflow_instance_records(id) ON DELETE CASCADE,
    template_id          INTEGER   REFERENCES ecm_workflow.workflow_templates(id),
    sla_deadline         TIMESTAMP NOT NULL,
    warning_threshold_at TIMESTAMP NOT NULL,
    escalation_deadline  TIMESTAMP,
    status               VARCHAR(30) NOT NULL DEFAULT 'ON_TRACK',
    warning_sent_at      TIMESTAMP,
    escalated_at         TIMESTAMP,
    breached_at          TIMESTAMP,
    completed_at         TIMESTAMP,
    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE INDEX IF NOT EXISTS idx_sla_status   ON ecm_workflow.workflow_sla_tracking(status);
CREATE INDEX IF NOT EXISTS idx_sla_deadline ON ecm_workflow.workflow_sla_tracking(sla_deadline);

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: Default workflow definition configs
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_definition_configs
(name, description, process_key, assigned_role, is_active, sla_hours)
VALUES
    ('General Document Review',
     'Default single-step review by backoffice team.',
     'document-single-review', 'ECM_BACKOFFICE', TRUE, 48),
    ('Underwriter Review',
     'Two-step: backoffice triage then underwriter approval.',
     'document-dual-review', 'ECM_REVIEWER', TRUE, 24),
    ('Compliance Review',
     'Compliance single-step review for KYC/regulatory documents.',
     'document-single-review', 'ECM_REVIEWER', TRUE, 24)
    ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: Default workflow templates
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_templates
(name, description, process_key, dsl_definition, status, is_default, sla_hours, warning_threshold_pct)
VALUES
    ('Default Single Review',
     'Single-step backoffice review for general documents.',
     'document-single-review',
     '{"processKey":"document-single-review","name":"Default Single Review","steps":[{"id":"step-1","type":"REVIEW","name":"Backoffice Review","assigneeRole":"ECM_BACKOFFICE","description":"Initial review by backoffice team","order":1}],"variables":{},"endStates":["COMPLETED_APPROVED","COMPLETED_REJECTED"]}'::jsonb,
     'PUBLISHED', TRUE, 48, 80),
    ('Mortgage Dual Review',
     'Two-stage review: backoffice triage then underwriter sign-off.',
     'mortgage-dual-review',
     '{"processKey":"mortgage-dual-review","name":"Mortgage Dual Review","steps":[{"id":"step-1","type":"REVIEW","name":"Backoffice Triage","assigneeRole":"ECM_BACKOFFICE","description":"Initial document check","order":1},{"id":"step-2","type":"APPROVE","name":"Underwriter Approval","assigneeRole":"ECM_REVIEWER","description":"Final credit decision","order":2}],"variables":{"reviewerGroup":"ECM_REVIEWER","seniorGroup":"ECM_BACKOFFICE"},"endStates":["COMPLETED_APPROVED","COMPLETED_REJECTED"]}'::jsonb,
     'PUBLISHED', FALSE, 24, 75),
    ('KYC Compliance Review',
     'Compliance team review for identity and AML documents.',
     'kyc-compliance-review',
     '{"processKey":"kyc-compliance-review","name":"KYC Compliance Review","steps":[{"id":"step-1","type":"VERIFY","name":"Identity Verification","assigneeRole":"ECM_REVIEWER","description":"Verify identity documents against records","order":1},{"id":"step-2","type":"SIGN","name":"Compliance Sign-off","assigneeRole":"ECM_REVIEWER","description":"Compliance officer sign-off","order":2}],"variables":{},"endStates":["COMPLETED_APPROVED","COMPLETED_REJECTED"]}'::jsonb,
     'DRAFT', FALSE, 48, 80)
    ON CONFLICT DO NOTHING;