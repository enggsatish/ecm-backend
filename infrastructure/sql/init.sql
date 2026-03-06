-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM Platform — Master Database Initialisation
-- Version: 2.0 (consolidated — replaces all per-module Flyway migration files)
--
-- Apply once to a fresh database:
--   psql -h localhost -U ecmuser -d ecmdb -f init.sql
--
-- After applying this file:
--   • Delete all V*.sql files from every module's db/migration directory.
--   • Flyway will find no pending migrations on startup and will be happy.
--   • Future schema changes go into new V1__*.sql files starting at V1 per module.
--
-- Schema layout:
--   ecm_core     — users, roles, departments, documents (shared domain)
--   ecm_audit    — audit_log (immutable, partitioned by month)
--   ecm_admin    — products, categories, retention, tenant config
--   ecm_workflow — BPM domain (Flowable bridge tables, templates, SLA tracking)
--   ecm_forms    — low-code eForms (definitions, submissions, DocuSign events)
--
-- Reconciliation notes vs. old migration chain:
--   • ecm_core.departments — final columns from V2 migration (name/code widened,
--     updated_at added, is_active NOT NULL). V1 narrow columns removed.
--   • ecm_core.documents — only columns mapped by Document.java entity.
--     Orphaned migration columns (content_type, storage_bucket, storage_key,
--     ocr_processed from V2/V3) are intentionally excluded — entity never mapped them.
--   • ecm_workflow.instance_status — was a PostgreSQL ENUM, now VARCHAR(30).
--     Entity uses @Enumerated(EnumType.STRING); VARCHAR is compatible and avoids
--     ALTER TYPE migrations when new statuses are added.
--   • ecm_admin.departments — is a VIEW over ecm_core.departments (from V2 unify).
--     Department JPA entity maps to ecm_admin.departments schema.
-- ═══════════════════════════════════════════════════════════════════════════════


-- ─────────────────────────────────────────────────────────────────────────────
-- SCHEMAS
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS ecm_core;
CREATE SCHEMA IF NOT EXISTS ecm_audit;
CREATE SCHEMA IF NOT EXISTS ecm_admin;
CREATE SCHEMA IF NOT EXISTS ecm_workflow;
CREATE SCHEMA IF NOT EXISTS ecm_forms;


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_CORE — shared domain tables
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Departments
-- Entity: ecm-identity (read), ecm-admin Department.java → ecm_admin.departments VIEW
-- Columns reconciled from V1 narrow + V2 widening migration.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.departments (
                                      id          SERIAL      PRIMARY KEY,
                                      name        VARCHAR(200) NOT NULL UNIQUE,
                                      code        VARCHAR(50)  NOT NULL UNIQUE,
                                      parent_id   INTEGER      REFERENCES ecm_core.departments(id) ON DELETE RESTRICT,
                                      is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                      created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                      updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_core_dept_parent ON ecm_core.departments(parent_id);
CREATE INDEX idx_core_dept_active ON ecm_core.departments(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Roles
-- Entity: ecm-identity Role.java
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.roles (
                                id          SERIAL       PRIMARY KEY,
                                name        VARCHAR(50)  NOT NULL UNIQUE,
                                description TEXT,
                                created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Users
-- Entity: ecm-identity User.java, ecm-document EcmUser.java (read-only projection),
--         ecm-admin AdminUserView.java (read-only projection)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.users (
                                id               SERIAL       PRIMARY KEY,
                                entra_object_id  VARCHAR(255) NOT NULL UNIQUE,   -- Okta JWT sub claim
                                email            VARCHAR(255) NOT NULL UNIQUE,
                                display_name     VARCHAR(255),
                                department_id    INTEGER      REFERENCES ecm_core.departments(id) ON DELETE SET NULL,
                                is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
                                last_login       TIMESTAMPTZ,
                                created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_core_users_entra    ON ecm_core.users(entra_object_id);
CREATE INDEX idx_core_users_email    ON ecm_core.users(email);
CREATE INDEX idx_core_users_dept     ON ecm_core.users(department_id);
CREATE INDEX idx_core_users_active   ON ecm_core.users(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- User ↔ Role join table
-- Entity: ecm-identity User.java @ManyToMany, ecm-admin UserRoleView.java
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.user_roles (
                                     user_id     INTEGER      NOT NULL REFERENCES ecm_core.users(id)  ON DELETE CASCADE,
                                     role_id     INTEGER      NOT NULL REFERENCES ecm_core.roles(id)  ON DELETE CASCADE,
                                     assigned_by INTEGER      REFERENCES ecm_core.users(id),
                                     assigned_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                     PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_core_user_roles_user ON ecm_core.user_roles(user_id);
CREATE INDEX idx_core_user_roles_role ON ecm_core.user_roles(role_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Document Categories (core)
-- Used by: ecm-document (categoryId FK), ecm-workflow (category_workflow_mappings)
-- NOTE: ecm-admin has its OWN document_categories table in ecm_admin schema.
--       They are separate — admin manages its own enriched hierarchy;
--       ecm_core.document_categories is the lightweight lookup used by documents.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.document_categories (
                                              id          SERIAL       PRIMARY KEY,
                                              name        VARCHAR(100) NOT NULL,
                                              code        VARCHAR(20)  NOT NULL UNIQUE,
                                              description TEXT,
                                              created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Documents
-- Entity: ecm-document Document.java
-- Only columns mapped by @Column annotations in the entity are included.
-- Orphaned columns from old migrations (content_type, storage_bucket,
-- storage_key, ocr_processed) are excluded — entity never mapped them.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.documents (
                                    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                    name                VARCHAR(500) NOT NULL,
                                    original_filename   VARCHAR(500) NOT NULL,
                                    mime_type           VARCHAR(100) NOT NULL,
                                    file_size_bytes     BIGINT,
                                    blob_storage_path   VARCHAR(1000) NOT NULL,
                                    category_id         INTEGER      REFERENCES ecm_core.document_categories(id) ON DELETE SET NULL,
                                    department_id       INTEGER      REFERENCES ecm_core.departments(id)         ON DELETE SET NULL,
                                    uploaded_by         INTEGER      REFERENCES ecm_core.users(id)               ON DELETE SET NULL,
                                    uploaded_by_email   VARCHAR(255),                  -- denormalised: avoids JOIN for display
                                    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING_OCR',
                                    version             INTEGER      NOT NULL DEFAULT 1,
                                    parent_doc_id       UUID         REFERENCES ecm_core.documents(id),
                                    is_latest_version   BOOLEAN      NOT NULL DEFAULT TRUE,
                                    ocr_completed       BOOLEAN      NOT NULL DEFAULT FALSE,
                                    extracted_text      TEXT,
                                    extracted_fields    JSONB,
                                    metadata            JSONB,
                                    tags                TEXT[],
                                    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_core_docs_dept      ON ecm_core.documents(department_id);
CREATE INDEX idx_core_docs_category  ON ecm_core.documents(category_id);
CREATE INDEX idx_core_docs_uploader  ON ecm_core.documents(uploaded_by);
CREATE INDEX idx_core_docs_status    ON ecm_core.documents(status);
CREATE INDEX idx_core_docs_created   ON ecm_core.documents(created_at DESC);
CREATE INDEX idx_core_docs_parent    ON ecm_core.documents(parent_doc_id);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_AUDIT — immutable audit trail
-- Entity: ecm-common AuditWriter.java (writes via @Async)
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE ecm_audit.audit_log (
                                     id              BIGSERIAL    PRIMARY KEY,
                                     event_type      VARCHAR(100) NOT NULL,
                                     user_id         INTEGER,                           -- ecm_core.users.id (soft ref)
                                     user_email      VARCHAR(255),
                                     entra_object_id VARCHAR(255),                      -- Okta JWT sub claim
                                     resource_type   VARCHAR(50),
                                     resource_id     VARCHAR(255),
                                     department_id   INTEGER,
                                     ip_address      INET,
                                     user_agent      TEXT,
                                     payload         JSONB,
                                     severity        VARCHAR(20)  NOT NULL DEFAULT 'INFO',
                                     session_id      VARCHAR(255),                      -- Okta session ID (sid claim)
                                     created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_entra_time ON ecm_audit.audit_log(entra_object_id, created_at DESC);
CREATE INDEX idx_audit_event_time ON ecm_audit.audit_log(event_type, created_at DESC);
CREATE INDEX idx_audit_resource   ON ecm_audit.audit_log(resource_type, resource_id);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_ADMIN — tenant configuration, product catalogue, document admin
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Document Categories (admin-enriched: hierarchical, with is_active + updated_at)
-- Entity: ecm-admin DocumentCategory.java → schema="ecm_admin"
-- Separate from ecm_core.document_categories (lightweight doc lookup).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.document_categories (
                                               id          SERIAL       PRIMARY KEY,
                                               name        VARCHAR(200) NOT NULL,
                                               code        VARCHAR(100) NOT NULL UNIQUE,
                                               parent_id   INTEGER      REFERENCES ecm_admin.document_categories(id) ON DELETE RESTRICT,
                                               description TEXT,
                                               is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                               created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                               updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_cat_parent ON ecm_admin.document_categories(parent_id);
CREATE INDEX idx_admin_cat_active ON ecm_admin.document_categories(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Products
-- Entity: ecm-admin Product.java
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.products (
                                    id              SERIAL       PRIMARY KEY,
                                    product_code    VARCHAR(50)  NOT NULL UNIQUE,
                                    display_name    VARCHAR(200) NOT NULL,
                                    description     TEXT,
                                    product_schema  JSONB,                             -- custom field definitions
                                    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
                                    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_product_code   ON ecm_admin.products(product_code);
CREATE INDEX idx_admin_product_active ON ecm_admin.products(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Product ↔ Category links
-- Entity: ecm-admin ProductCategoryLink.java
-- workflow_definition_id is a soft reference (no DB FK — cross-schema)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.product_category_links (
                                                  id                     SERIAL  PRIMARY KEY,
                                                  product_id             INTEGER NOT NULL REFERENCES ecm_admin.products(id)            ON DELETE CASCADE,
                                                  category_id            INTEGER NOT NULL REFERENCES ecm_admin.document_categories(id) ON DELETE CASCADE,
                                                  workflow_definition_id INTEGER,                    -- soft ref: ecm_workflow.workflow_definition_configs.id
                                                  is_active              BOOLEAN NOT NULL DEFAULT TRUE,
                                                  created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                  UNIQUE (product_id, category_id)
);

CREATE INDEX idx_admin_pcl_product  ON ecm_admin.product_category_links(product_id);
CREATE INDEX idx_admin_pcl_category ON ecm_admin.product_category_links(category_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Retention Policies
-- Entity: ecm-admin RetentionPolicy.java
-- category_id and product_code are soft references (cross-schema, no DB FK)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.retention_policies (
                                              id                  SERIAL       PRIMARY KEY,
                                              name                VARCHAR(200) NOT NULL,
                                              category_id         INTEGER,                       -- soft ref: ecm_admin.document_categories.id
                                              product_code        VARCHAR(50),                   -- soft ref: ecm_admin.products.product_code
                                              archive_after_days  INTEGER      NOT NULL DEFAULT 365,
                                              purge_after_days    INTEGER      NOT NULL DEFAULT 2555,
                                              is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
                                              created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                              updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_rp_category ON ecm_admin.retention_policies(category_id);
CREATE INDEX idx_admin_rp_active   ON ecm_admin.retention_policies(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Tenant Configuration
-- Entity: ecm-admin TenantConfig.java
-- Simple key-value store for white-label branding and platform settings.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.tenant_config (
                                         key         VARCHAR(100) PRIMARY KEY,
                                         value       TEXT         NOT NULL,
                                         description VARCHAR(500),
                                         updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.departments — VIEW over ecm_core.departments
--
-- WHY a VIEW:
--   The Department JPA entity (ecm-admin) maps to schema="ecm_admin".
--   Departments are the authoritative source of truth in ecm_core, shared by
--   identity, document, and workflow services. Rather than duplicate the table,
--   ecm_admin exposes a view so admin queries resolve without cross-schema joins.
--   The view is transparent — any SELECT on ecm_admin.departments reads from
--   ecm_core.departments directly.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE VIEW ecm_admin.departments AS
SELECT id, name, code, parent_id, is_active, created_at, updated_at
FROM ecm_core.departments;


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_WORKFLOW — Flowable BPM bridge tables + template engine + SLA tracking
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Groups
-- Entity: ecm-workflow WorkflowGroup.java
-- group_key is passed to Flowable as candidateGroup. Convention: "group:<id>"
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_groups (
                                              id          SERIAL       PRIMARY KEY,
                                              name        VARCHAR(200) NOT NULL,
                                              description VARCHAR(500),
                                              group_key   VARCHAR(100) NOT NULL UNIQUE,           -- e.g. "group:1"
                                              is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                              created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                              updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Group Members
-- Entity: ecm-workflow WorkflowGroupMember.java
-- user_id is a soft ref to ecm_core.users.id (cross-schema, no DB FK)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_group_members (
                                                     id        SERIAL  PRIMARY KEY,
                                                     group_id  INTEGER NOT NULL REFERENCES ecm_workflow.workflow_groups(id) ON DELETE CASCADE,
                                                     user_id   INTEGER NOT NULL,                         -- soft ref: ecm_core.users.id
                                                     added_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                     UNIQUE (group_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Definition Configs
-- Entity: ecm-workflow WorkflowDefinitionConfig.java
-- Links a Flowable BPMN process_key to assignment rules (role or group).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_definition_configs (
                                                          id                  SERIAL       PRIMARY KEY,
                                                          name                VARCHAR(200) NOT NULL,
                                                          description         VARCHAR(500),
                                                          process_key         VARCHAR(100) NOT NULL,           -- Flowable process definition key
                                                          assigned_role       VARCHAR(100) NOT NULL DEFAULT 'ECM_BACKOFFICE',
                                                          assigned_group_id   INTEGER      REFERENCES ecm_workflow.workflow_groups(id),
                                                          is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
                                                          sla_hours           INTEGER,
                                                          created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                                          updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Category → Workflow Mappings (legacy direct mapping)
-- Entity: ecm-workflow CategoryWorkflowMapping.java
-- category_id is a soft ref to ecm_core.document_categories.id (cross-schema)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.category_workflow_mappings (
                                                         id                     SERIAL  PRIMARY KEY,
                                                         category_id            INTEGER NOT NULL UNIQUE,     -- soft ref: ecm_core.document_categories.id
                                                         workflow_definition_id INTEGER NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
                                                         is_active              BOOLEAN NOT NULL DEFAULT TRUE,
                                                         created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Instance Records
-- Entity: ecm-workflow WorkflowInstanceRecord.java
--
-- status: VARCHAR(30) not PostgreSQL ENUM — entity uses @Enumerated(EnumType.STRING).
-- Using VARCHAR avoids ALTER TYPE migrations when new statuses are added.
-- Valid values: ACTIVE | INFO_REQUESTED | COMPLETED_APPROVED | COMPLETED_REJECTED | CANCELLED
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_instance_records (
                                                        id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                                        process_instance_id    VARCHAR(100) NOT NULL UNIQUE,  -- Flowable's internal ID
                                                        document_id            UUID         NOT NULL,          -- soft ref: ecm_core.documents.id
                                                        document_name          VARCHAR(500),                   -- denormalised for display
                                                        category_id            INTEGER,
                                                        workflow_definition_id INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
                                                        status                 VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
                                                        trigger_type           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
                                                        started_by_subject     VARCHAR(255) NOT NULL,          -- Okta JWT sub claim
                                                        started_by_email       VARCHAR(255),
                                                        template_id            INTEGER,                        -- soft ref: workflow_templates.id
                                                        completed_at           TIMESTAMPTZ,
                                                        final_comment          TEXT,
                                                        created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                                        updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wir_document_id ON ecm_workflow.workflow_instance_records(document_id);
CREATE INDEX idx_wir_status      ON ecm_workflow.workflow_instance_records(status);
CREATE INDEX idx_wir_started_by  ON ecm_workflow.workflow_instance_records(started_by_subject);
CREATE INDEX idx_wir_created_at  ON ecm_workflow.workflow_instance_records(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Templates (low-code template engine — Sprint B+)
-- Entity: ecm-workflow WorkflowTemplate.java
-- dsl_definition JSONB holds the WorkflowTemplateDsl (steps, conditions, SLA).
-- status: DRAFT | PUBLISHED | DEPRECATED
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_templates (
                                                 id                      SERIAL       PRIMARY KEY,
                                                 name                    VARCHAR(200) NOT NULL,
                                                 description             TEXT,
                                                 process_key             VARCHAR(200) UNIQUE,          -- populated on publish
                                                 dsl_definition          JSONB        NOT NULL,
                                                 status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
                                                 version                 INTEGER      NOT NULL DEFAULT 1,
                                                 is_default              BOOLEAN      NOT NULL DEFAULT FALSE,
    -- SLA config
                                                 sla_hours               INTEGER      NOT NULL DEFAULT 48,
                                                 warning_threshold_pct   INTEGER      NOT NULL DEFAULT 80,
                                                 escalation_hours        INTEGER,
                                                 escalation_group_key    VARCHAR(100),
    -- Flowable deployment refs (populated on publish)
                                                 flowable_deployment_id  VARCHAR(200),
                                                 flowable_process_def_id VARCHAR(200),
    -- audit
                                                 created_by              VARCHAR(200),
                                                 created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
                                                 updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Template Mappings (hierarchy-aware: product + category → template)
-- Entity: ecm-workflow WorkflowTemplateMapping.java
-- product_id and category_id are soft refs (cross-schema, no DB FK)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_template_mappings (
                                                         id          SERIAL   PRIMARY KEY,
                                                         template_id INTEGER  NOT NULL REFERENCES ecm_workflow.workflow_templates(id),
                                                         product_id  INTEGER,                                  -- NULL = any product; soft ref: ecm_admin.products.id
                                                         category_id INTEGER  NOT NULL,                        -- soft ref: ecm_admin.document_categories.id
                                                         priority    INTEGER  NOT NULL DEFAULT 100,
                                                         is_active   BOOLEAN  NOT NULL DEFAULT TRUE,
                                                         created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                                                         CONSTRAINT uq_template_mapping UNIQUE (product_id, category_id)
);

CREATE INDEX idx_wtm_category ON ecm_workflow.workflow_template_mappings(category_id);
CREATE INDEX idx_wtm_product  ON ecm_workflow.workflow_template_mappings(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow SLA Tracking
-- Entity: ecm-workflow WorkflowSlaTracking.java
-- Uses TIMESTAMP (no tz) — entity maps LocalDateTime fields.
-- status: ON_TRACK | WARNING | ESCALATED | BREACHED | COMPLETED
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_sla_tracking (
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

CREATE INDEX idx_sla_status   ON ecm_workflow.workflow_sla_tracking(status);
CREATE INDEX idx_sla_deadline ON ecm_workflow.workflow_sla_tracking(sla_deadline);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_FORMS — low-code eForms engine
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Product Type Catalogue
-- Configurable per tenant. Referenced by form_definitions.product_type_code.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.product_types (
                                         id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                         tenant_id   VARCHAR(100) NOT NULL DEFAULT 'default',
                                         code        VARCHAR(100) NOT NULL,
                                         label       VARCHAR(255) NOT NULL,
                                         description TEXT,
                                         icon        VARCHAR(100),
                                         sort_order  INTEGER      NOT NULL DEFAULT 0,
                                         active      BOOLEAN      NOT NULL DEFAULT TRUE,
                                         created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                         CONSTRAINT uq_product_type UNIQUE (tenant_id, code)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Type Catalogue
-- Configurable per tenant. Referenced by form_definitions.form_type_code.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_types (
                                      id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                      tenant_id   VARCHAR(100) NOT NULL DEFAULT 'default',
                                      code        VARCHAR(100) NOT NULL,
                                      label       VARCHAR(255) NOT NULL,
                                      description TEXT,
                                      sort_order  INTEGER      NOT NULL DEFAULT 0,
                                      active      BOOLEAN      NOT NULL DEFAULT TRUE,
                                      created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                      CONSTRAINT uq_form_type UNIQUE (tenant_id, code)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Definitions
-- Entity: ecm-eforms FormDefinition.java
-- schema JSONB holds the full FormSchema DSL (sections, fields, globalRules).
-- Lifecycle: DRAFT → PUBLISHED → ARCHIVED → DEPRECATED
-- One PUBLISHED version per (tenant_id, form_key) enforced by partial unique index.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_definitions (
                                            id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                            tenant_id            VARCHAR(100) NOT NULL DEFAULT 'default',

    -- identification
                                            form_key             VARCHAR(200) NOT NULL,          -- URL-safe, e.g. "mortgage-application"
                                            name                 VARCHAR(255) NOT NULL,
                                            description          TEXT,
                                            product_type_code    VARCHAR(100),                   -- soft ref: ecm_forms.product_types.code
                                            form_type_code       VARCHAR(100),                   -- soft ref: ecm_forms.form_types.code
                                            version              INTEGER      NOT NULL DEFAULT 1,
                                            tags                 TEXT[],

    -- lifecycle
                                            status               VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',

    -- schema payloads (JSONB — nullable, Hibernate writes null if not set)
                                            schema               JSONB,
                                            ui_config            JSONB,
                                            workflow_config      JSONB,
                                            docusign_config      JSONB,

    -- optional document template ref (soft ref: ecm_core.documents.id)
                                            document_template_id UUID,

    -- publish tracking
                                            published_at         TIMESTAMPTZ,
                                            published_by         VARCHAR(255),
                                            archived_at          TIMESTAMPTZ,
                                            archived_by          VARCHAR(255),

    -- audit
                                            created_by           VARCHAR(255) NOT NULL,
                                            updated_by           VARCHAR(255),
                                            created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                            updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

                                            CONSTRAINT uq_form_def_version UNIQUE (tenant_id, form_key, version)
);

-- Enforce: only ONE published version per (tenant, form_key) at any time
CREATE UNIQUE INDEX idx_uq_published_form
    ON ecm_forms.form_definitions (tenant_id, form_key)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_form_def_tenant       ON ecm_forms.form_definitions(tenant_id);
CREATE INDEX idx_form_def_form_key     ON ecm_forms.form_definitions(form_key);
CREATE INDEX idx_form_def_status       ON ecm_forms.form_definitions(status);
CREATE INDEX idx_form_def_product_type ON ecm_forms.form_definitions(product_type_code);

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Submissions
-- Entity: ecm-eforms FormSubmission.java
-- Stores filled form data + DocuSign envelope tracking + review workflow state.
-- form_schema_snapshot is a point-in-time copy of the schema at submission time
-- (compliance requirement — the form definition can change after submission).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_submissions (
                                            id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                            tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',

    -- which form
                                            form_definition_id    UUID         NOT NULL REFERENCES ecm_forms.form_definitions(id),
                                            form_key              VARCHAR(200) NOT NULL,
                                            form_version          INTEGER      NOT NULL,
                                            form_schema_snapshot  JSONB,                         -- point-in-time schema copy

    -- filled data
                                            submission_data       JSONB,

    -- lifecycle
    -- DRAFT | SUBMITTED | PENDING_SIGNATURE | SIGNED | SIGN_DECLINED
    -- IN_REVIEW | APPROVED | REJECTED | COMPLETED | WITHDRAWN
                                            status                VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',

    -- submitter (always authenticated)
                                            submitted_by          VARCHAR(255) NOT NULL,         -- Okta JWT sub claim
                                            submitted_by_name     VARCHAR(255),
                                            submitted_at          TIMESTAMPTZ,

    -- DocuSign tracking
                                            docusign_envelope_id  VARCHAR(255),
                                            docusign_status       VARCHAR(100),
                                            docusign_sent_at      TIMESTAMPTZ,
                                            docusign_completed_at TIMESTAMPTZ,
                                            signed_document_id    UUID,                          -- MinIO ref: completed signed PDF
                                            draft_document_id     UUID,                          -- MinIO ref: generated draft PDF

    -- workflow bridge
                                            workflow_instance_id  VARCHAR(255),                  -- soft ref: ecm_workflow.workflow_instance_records

    -- backoffice review
                                            assigned_to           VARCHAR(255),
                                            assigned_at           TIMESTAMPTZ,
                                            review_notes          TEXT,
                                            reviewed_by           VARCHAR(255),
                                            reviewed_at           TIMESTAMPTZ,

    -- request metadata
                                            channel               VARCHAR(100) NOT NULL DEFAULT 'WEB',
                                            ip_address            VARCHAR(50),
                                            user_agent            TEXT,

                                            created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                            updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sub_tenant        ON ecm_forms.form_submissions(tenant_id);
CREATE INDEX idx_sub_form_key      ON ecm_forms.form_submissions(form_key);
CREATE INDEX idx_sub_status        ON ecm_forms.form_submissions(status);
CREATE INDEX idx_sub_submitted_by  ON ecm_forms.form_submissions(submitted_by);
CREATE INDEX idx_sub_envelope      ON ecm_forms.form_submissions(docusign_envelope_id);
CREATE INDEX idx_sub_workflow      ON ecm_forms.form_submissions(workflow_instance_id);
CREATE INDEX idx_sub_assigned      ON ecm_forms.form_submissions(assigned_to);
-- GIN index for fast JSONB field queries (pre-OpenSearch local dev)
CREATE INDEX idx_sub_data_gin      ON ecm_forms.form_submissions USING GIN (submission_data);

-- ─────────────────────────────────────────────────────────────────────────────
-- DocuSign Webhook Event Log
-- Entity: ecm-eforms DocuSignEvent.java
-- Idempotent store for all inbound DocuSign Connect events.
-- Deduplication: processed flag + unique (envelope_id, event_type) check in code.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.docusign_events (
                                           id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                           envelope_id VARCHAR(255) NOT NULL,
                                           event_type  VARCHAR(100),
                                           raw_payload JSONB,
                                           processed   BOOLEAN      NOT NULL DEFAULT FALSE,
                                           error       TEXT,
                                           received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ds_envelope   ON ecm_forms.docusign_events(envelope_id);
CREATE INDEX idx_ds_processed  ON ecm_forms.docusign_events(processed);


-- ═══════════════════════════════════════════════════════════════════════════════
-- SEED DATA
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Roles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.roles (name, description) VALUES
                                                   ('ECM_ADMIN',      'Full system administration access'),
                                                   ('ECM_DESIGNER',   'Can create and publish eForms'),
                                                   ('ECM_BACKOFFICE', 'Standard back-office document and workflow access'),
                                                   ('ECM_REVIEWER',   'Can review and approve workflow tasks'),
                                                   ('ECM_READONLY',   'Read-only access to assigned departments')
    ON CONFLICT (name) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Departments (canonical set — used by identity, document, workflow)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.departments (name, code) VALUES
                                                  ('Head Office',      'HQ'),
                                                  ('Operations',       'OPS'),
                                                  ('Underwriting',     'UW'),
                                                  ('Back Office',      'BO'),
                                                  ('Document Control', 'DC'),
                                                  ('Finance',          'FIN'),
                                                  ('Human Resources',  'HR'),
                                                  ('Legal',            'LEG'),
                                                  ('IT',               'IT')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Document Categories (lightweight lookup for documents service)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.document_categories (name, code, description) VALUES
                                                                       ('Mortgage',              'MORTGAGE',  'Mortgage application documents'),
                                                                       ('Auto Loan',             'AUTO_LOAN', 'Auto loan application documents'),
                                                                       ('Identity Verification', 'IDENTITY',  'KYC and identity documents'),
                                                                       ('Financial Statements',  'FINANCIAL', 'Income, tax and financial records'),
                                                                       ('Legal Agreements',      'LEGAL',     'Signed legal and compliance documents'),
                                                                       ('Invoice',               'INV',       'Vendor and customer invoices'),
                                                                       ('Contract',              'CTR',       'Legal contracts and agreements'),
                                                                       ('HR Document',           'HRD',       'HR records and payslips'),
                                                                       ('Report',                'RPT',       'Internal and external reports'),
                                                                       ('Correspondence',        'COR',       'Emails and letters'),
                                                                       ('Scanned Form',          'SCF',       'Scanned physical forms')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Tenant Config (white-label defaults)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.tenant_config (key, value, description) VALUES
                                                                  ('tenant.name',          'ECM Platform', 'Organisation display name'),
                                                                  ('tenant.logo_url',      '',             'Logo URL for header branding'),
                                                                  ('tenant.primary_color', '#002347',      'Brand primary colour (hex)'),
                                                                  ('tenant.support_email', '',             'Support email shown in UI footer'),
                                                                  ('tenant.timezone',      'UTC',          'Default timezone for date display')
    ON CONFLICT (key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Document Categories (admin-enriched hierarchy)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.document_categories (name, code, description) VALUES
                                                                        ('Mortgage',              'MORTGAGE',  'Mortgage application documents'),
                                                                        ('Auto Loan',             'AUTO_LOAN', 'Auto loan application documents'),
                                                                        ('Identity Verification', 'IDENTITY',  'KYC and identity documents'),
                                                                        ('Financial Statements',  'FINANCIAL', 'Income, tax and financial records'),
                                                                        ('Legal Agreements',      'LEGAL',     'Signed legal and compliance documents')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Products
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.products (product_code, display_name, description, product_schema) VALUES
                                                                                             ('MORTGAGE',
                                                                                              'Mortgage Application',
                                                                                              'Residential mortgage origination product',
                                                                                              '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"propertyAddress","label":"Property Address","type":"text","required":true}]}'::jsonb),
                                                                                             ('AUTO_LOAN',
                                                                                              'Auto Loan Application',
                                                                                              'Vehicle purchase and refinance loans',
                                                                                              '{"fields":[{"key":"vehicleVin","label":"Vehicle VIN","type":"text","required":true},{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true}]}'::jsonb),
                                                                                             ('PERSONAL_LOAN',
                                                                                              'Personal Loan',
                                                                                              'Unsecured personal lending products',
                                                                                              '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"purpose","label":"Loan Purpose","type":"text","required":true}]}'::jsonb)
    ON CONFLICT (product_code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow: Default Workflow Definition Configs
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_definition_configs
(name, description, process_key, assigned_role, is_active, sla_hours)
VALUES
    ('General Document Review',
     'Default single-step review by backoffice team.',
     'document-single-review', 'ECM_BACKOFFICE', TRUE, 48),
    ('Underwriter Review',
     'Two-step: backoffice triage then underwriter approval. Used for loan/credit documents.',
     'document-dual-review', 'ECM_REVIEWER', TRUE, 24),
    ('Compliance Review',
     'Compliance team single-step review. Used for KYC and regulatory documents.',
     'document-single-review', 'ECM_REVIEWER', TRUE, 24)
    ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow: Default Template (migration target for legacy category_mappings)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_workflow.workflow_templates
(name, description, process_key, dsl_definition, status, is_default, sla_hours)
VALUES
    ('Default Single Review',
     'Default workflow template for general document review.',
     'document-single-review',
     '{"processKey":"document-single-review","name":"Default Single Review","steps":[],"variables":{},"endStates":[]}'::jsonb,
     'PUBLISHED', TRUE, 48),
    ('Mortgage Dual Review',
     'Two-stage review for mortgage applications.',
     'mortgage-dual-review',
     '{"processKey":"mortgage-dual-review","name":"Mortgage Dual Review","variables":{"reviewerGroup":"ECM_REVIEWER","seniorGroup":"ECM_BACKOFFICE"},"steps":[],"endStates":[]}'::jsonb,
     'DRAFT', FALSE, 24)
    ON CONFLICT DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms: Product Types (financial institution defaults)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_forms.product_types
(tenant_id, code, label, description, icon, sort_order)
VALUES
    ('default', 'MORTGAGE',        'Mortgage',         'Residential & commercial mortgage products',    'home',         1),
    ('default', 'AUTO_LOAN',       'Auto Loan',        'Vehicle financing products',                    'car',          2),
    ('default', 'PERSONAL_LOAN',   'Personal Loan',    'Unsecured personal lending',                    'user',         3),
    ('default', 'CREDIT_CARD',     'Credit Card',      'Credit card applications and amendments',       'credit-card',  4),
    ('default', 'INSURANCE',       'Insurance',        'Life, property and liability insurance',        'shield',       5),
    ('default', 'INVESTMENT',      'Investment',       'Investment accounts and products',               'trending-up',  6),
    ('default', 'ACCOUNT_OPENING', 'Account Opening',  'Personal and business account opening',         'briefcase',    7),
    ('default', 'BUSINESS_LOAN',   'Business Loan',    'Commercial lending products',                   'building',     8),
    ('default', 'COMPLIANCE',      'Compliance',       'AML, KYC and regulatory compliance forms',      'check-square', 9)
    ON CONFLICT ON CONSTRAINT uq_product_type DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms: Form Types
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_forms.form_types
(tenant_id, code, label, description, sort_order)
VALUES
    ('default', 'APPLICATION', 'Application',  'New product or account applications',           1),
    ('default', 'KYC',         'KYC',          'Know Your Customer identity verification',      2),
    ('default', 'AML',         'AML',          'Anti-Money Laundering disclosure',              3),
    ('default', 'DISCLOSURE',  'Disclosure',   'Regulatory and product disclosures',            4),
    ('default', 'CONSENT',     'Consent',      'Customer consent and authorisation',            5),
    ('default', 'AMENDMENT',   'Amendment',    'Amendment to existing product or account',      6),
    ('default', 'CLAIM',       'Claim',        'Insurance or dispute claim forms',              7),
    ('default', 'RENEWAL',     'Renewal',      'Product renewal and extension',                 8),
    ('default', 'ONBOARDING',  'Onboarding',   'Employee or customer onboarding forms',         9),
    ('default', 'OTHER',       'Other',        'General purpose forms',                        10)
    ON CONFLICT ON CONSTRAINT uq_form_type DO NOTHING;