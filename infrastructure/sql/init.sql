-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM Platform — Master Database Initialisation  v3.0
--
-- REPLACES: infrastructure/sql/init.sql  (v2.0)
-- FOLDS IN:
--   ecm-admin   V2__add_hierarchy.sql
--   ecm-admin   V3__retention_hierarchy.sql
--   ecm-document V1__add_segment_product_columns.sql
--   ecm-document V5__add_segment_context.sql
--   ecm-workflow V1__workflow_schema_baseline.sql (idempotent guards)
--   ecm-workflow V2__add_bpmn_xml_to_workflow_templates.sql
-- NEW (Sprint 1 / Sprint 2):
--   ecm_core.parties                  — customer / party records (manually-assigned external_id)
--   ecm_core.party_product_enrollments — party ↔ product line enrolments
--   ecm_core.documents.party_id       — soft FK to parties
--   ecm_forms.form_submissions.party_id — party context on form fills
--   ecm_admin.integration_configs     — typed credential store (DocuSign, Azure AI, …)
--   ecm_workflow.workflow_task_history — per-task action audit trail
--
-- HOW TO APPLY (clean start):
--   psql -h localhost -U ecmuser -d ecmdb -f init_v3.sql
--
-- AFTER APPLYING:
--   1. Delete ALL V*.sql files from every module's db/migration/ directory.
--   2. Add a single V1__baseline.sql (empty or "SELECT 1;") to each module's
--      db/migration/ directory so Flyway creates its schema_history table.
--   3. Future schema changes go into V2__*.sql in the relevant module.
--
-- Schema layout:
--   ecm_core     — shared domain: users, parties, documents
--   ecm_audit    — immutable audit trail
--   ecm_admin    — product catalogue, segments, tenant config, integrations
--   ecm_workflow — Flowable BPM bridge, templates, SLA, task history
--   ecm_forms    — eForms engine: definitions, submissions, DocuSign events
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
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.departments (
                                      id          SERIAL       PRIMARY KEY,
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
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.roles (
                                id          SERIAL      PRIMARY KEY,
                                name        VARCHAR(50) NOT NULL UNIQUE,
                                description TEXT,
                                created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Users
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.users (
                                id               SERIAL       PRIMARY KEY,
                                entra_object_id  VARCHAR(255) NOT NULL UNIQUE,
                                email            VARCHAR(255) NOT NULL UNIQUE,
                                display_name     VARCHAR(255),
                                department_id    INTEGER      REFERENCES ecm_core.departments(id) ON DELETE SET NULL,
                                is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
                                last_login       TIMESTAMPTZ,
                                created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_core_users_entra  ON ecm_core.users(entra_object_id);
CREATE INDEX idx_core_users_email  ON ecm_core.users(email);
CREATE INDEX idx_core_users_dept   ON ecm_core.users(department_id);
CREATE INDEX idx_core_users_active ON ecm_core.users(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- User ↔ Role join
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.user_roles (
                                     user_id     INTEGER     NOT NULL REFERENCES ecm_core.users(id)  ON DELETE CASCADE,
                                     role_id     INTEGER     NOT NULL REFERENCES ecm_core.roles(id)  ON DELETE CASCADE,
                                     assigned_by INTEGER     REFERENCES ecm_core.users(id),
                                     assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                     PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_core_user_roles_user ON ecm_core.user_roles(user_id);
CREATE INDEX idx_core_user_roles_role ON ecm_core.user_roles(role_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Document Categories (core — lightweight lookup for ecm-document)
-- NOTE: ecm_admin.document_categories is the admin-enriched version.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.document_categories (
                                              id          SERIAL       PRIMARY KEY,
                                              name        VARCHAR(100) NOT NULL,
                                              code        VARCHAR(20)  NOT NULL UNIQUE,
                                              description TEXT,
                                              created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Parties  (Customer / Client entity)
--
-- Design decisions:
--   • external_id is the MANUALLY-ASSIGNED human-visible ID (e.g. COMM-001,
--     SMB-042, RET-10045).  NOT NULL UNIQUE — admin must supply it on create.
--   • id (UUID) is the internal PK used for all FK references.
--   • party_type: COMMERCIAL | SMB | RETAIL
--   • COMMERCIAL parties may parent SMB parties via parent_party_id.
--   • RETAIL parties may NOT have a parent (enforced by CHECK constraint).
--   • segment_id is a soft ref to ecm_admin.segments.id (cross-schema, no DB FK).
--   • Written by ecm-admin via JdbcTemplate (cross-schema write rule).
--   • Read by ecm-document, ecm-eforms via read-only JPA projections.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.parties (
                                  id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                  external_id      VARCHAR(100) NOT NULL UNIQUE,  -- manually assigned by admin (e.g. COMM-001)
                                  party_type       VARCHAR(20)  NOT NULL,
                                  segment_id       INTEGER      NOT NULL,          -- soft ref → ecm_admin.segments.id
                                  display_name     VARCHAR(255) NOT NULL,
                                  short_name       VARCHAR(100),
                                  registration_no  VARCHAR(100),                   -- business reg / tax number
                                  parent_party_id  UUID         REFERENCES ecm_core.parties(id) ON DELETE RESTRICT,
                                  is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
                                  notes            TEXT,
                                  created_by       VARCHAR(255) NOT NULL,          -- Entra Object ID of creator
                                  created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                  CONSTRAINT ck_party_type        CHECK (party_type IN ('COMMERCIAL', 'SMB', 'RETAIL')),
                                  CONSTRAINT ck_retail_no_parent  CHECK (party_type != 'RETAIL' OR parent_party_id IS NULL)
    );
CREATE INDEX idx_party_external    ON ecm_core.parties(external_id);
CREATE INDEX idx_party_type        ON ecm_core.parties(party_type);
CREATE INDEX idx_party_segment     ON ecm_core.parties(segment_id);
CREATE INDEX idx_party_parent      ON ecm_core.parties(parent_party_id);
CREATE INDEX idx_party_active      ON ecm_core.parties(is_active);
CREATE INDEX idx_party_name        ON ecm_core.parties(display_name);

-- ─────────────────────────────────────────────────────────────────────────────
-- Party Product Enrolments
--
-- Links a party to the product lines (and optionally specific products)
-- they are enrolled in.  Used by the document upload form to present only
-- the relevant product lines for the selected customer.
--
-- product_line_id and product_id are soft refs (cross-schema, no DB FK).
-- Written by ecm-admin via JdbcTemplate.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.party_product_enrollments (
                                                    id              SERIAL      PRIMARY KEY,
                                                    party_id        UUID        NOT NULL REFERENCES ecm_core.parties(id) ON DELETE CASCADE,
                                                    product_line_id INTEGER     NOT NULL,   -- soft ref → ecm_admin.product_lines.id
                                                    product_id      INTEGER,                -- soft ref → ecm_admin.products.id (optional)
                                                    enrolled_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                    enrolled_by     VARCHAR(255) NOT NULL,
                                                    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
                                                    UNIQUE (party_id, product_line_id, product_id)
);
CREATE INDEX idx_ppe_party        ON ecm_core.party_product_enrollments(party_id);
CREATE INDEX idx_ppe_product_line ON ecm_core.party_product_enrollments(product_line_id);
CREATE INDEX idx_ppe_active       ON ecm_core.party_product_enrollments(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Documents
--
-- Columns from original init.sql + V1/V5 segment migrations + new party_id.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.documents (
                                    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                                    name                VARCHAR(500)  NOT NULL,
                                    original_filename   VARCHAR(500)  NOT NULL,
                                    mime_type           VARCHAR(100)  NOT NULL,
                                    file_size_bytes     BIGINT,
                                    blob_storage_path   VARCHAR(1000) NOT NULL,
                                    category_id         INTEGER       REFERENCES ecm_core.document_categories(id) ON DELETE SET NULL,
                                    department_id       INTEGER       REFERENCES ecm_core.departments(id)          ON DELETE SET NULL,
                                    uploaded_by         INTEGER       REFERENCES ecm_core.users(id)                ON DELETE SET NULL,
                                    uploaded_by_email   VARCHAR(255),
                                    status              VARCHAR(50)   NOT NULL DEFAULT 'PENDING_OCR',
                                    version             INTEGER       NOT NULL DEFAULT 1,
                                    parent_doc_id       UUID          REFERENCES ecm_core.documents(id),
                                    is_latest_version   BOOLEAN       NOT NULL DEFAULT TRUE,
                                    ocr_completed       BOOLEAN       NOT NULL DEFAULT FALSE,
                                    extracted_text      TEXT,
                                    extracted_fields    JSONB,
                                    metadata            JSONB,
                                    tags                TEXT[],
    -- Hierarchy context (soft refs — cross-schema, no DB FK)
                                    segment_id          INTEGER,      -- soft ref → ecm_admin.segments.id
                                    product_line_id     INTEGER,      -- soft ref → ecm_admin.product_lines.id
    -- Party link (soft FK via UUID — same schema, but written by different modules)
                                    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
                                    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                                    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_core_docs_dept         ON ecm_core.documents(department_id);
CREATE INDEX idx_core_docs_category     ON ecm_core.documents(category_id);
CREATE INDEX idx_core_docs_uploader     ON ecm_core.documents(uploaded_by);
CREATE INDEX idx_core_docs_status       ON ecm_core.documents(status);
CREATE INDEX idx_core_docs_created      ON ecm_core.documents(created_at DESC);
CREATE INDEX idx_core_docs_parent       ON ecm_core.documents(parent_doc_id);
CREATE INDEX idx_core_docs_segment      ON ecm_core.documents(segment_id);
CREATE INDEX idx_core_docs_product_line ON ecm_core.documents(product_line_id);
CREATE INDEX idx_core_docs_party        ON ecm_core.documents(party_id);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_AUDIT — immutable audit trail
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE ecm_audit.audit_log (
                                     id              BIGSERIAL    PRIMARY KEY,
                                     event_type      VARCHAR(100) NOT NULL,
                                     user_id         INTEGER,
                                     user_email      VARCHAR(255),
                                     entra_object_id VARCHAR(255),
                                     resource_type   VARCHAR(50),
                                     resource_id     VARCHAR(255),
                                     department_id   INTEGER,
                                     ip_address      INET,
                                     user_agent      TEXT,
                                     payload         JSONB,
                                     severity        VARCHAR(20)  NOT NULL DEFAULT 'INFO',
                                     session_id      VARCHAR(255),
                                     created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_entra_time ON ecm_audit.audit_log(entra_object_id, created_at DESC);
CREATE INDEX idx_audit_event_time ON ecm_audit.audit_log(event_type, created_at DESC);
CREATE INDEX idx_audit_resource   ON ecm_audit.audit_log(resource_type, resource_id);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_ADMIN — product catalogue, segments, tenant config, integrations
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Segments  (from V2__add_hierarchy.sql)
-- Top of the financial hierarchy: Retail | Commercial | Small Business
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.segments (
                                    id          SERIAL       PRIMARY KEY,
                                    name        VARCHAR(100) NOT NULL,
                                    code        VARCHAR(20)  NOT NULL UNIQUE,  -- 'RETAIL', 'COMMERCIAL', 'SMB'
                                    description TEXT,
                                    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_seg_active ON ecm_admin.segments(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Product Lines  (from V2__add_hierarchy.sql)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.product_lines (
                                         id          SERIAL       PRIMARY KEY,
                                         segment_id  INTEGER      NOT NULL REFERENCES ecm_admin.segments(id) ON DELETE RESTRICT,
                                         name        VARCHAR(100) NOT NULL,
                                         code        VARCHAR(30)  NOT NULL UNIQUE,
                                         description TEXT,
                                         is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                         created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                         updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pl_segment ON ecm_admin.product_lines(segment_id);
CREATE INDEX idx_pl_active  ON ecm_admin.product_lines(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Document Categories (admin-enriched: hierarchical, with segment/product_line)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.document_categories (
                                               id              SERIAL       PRIMARY KEY,
                                               name            VARCHAR(200) NOT NULL,
                                               code            VARCHAR(100) NOT NULL UNIQUE,
                                               parent_id       INTEGER      REFERENCES ecm_admin.document_categories(id) ON DELETE RESTRICT,
                                               description     TEXT,
                                               segment_id      INTEGER      REFERENCES ecm_admin.segments(id),
                                               product_line_id INTEGER      REFERENCES ecm_admin.product_lines(id),
                                               is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
                                               created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                               updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_cat_parent      ON ecm_admin.document_categories(parent_id);
CREATE INDEX idx_admin_cat_active      ON ecm_admin.document_categories(is_active);
CREATE INDEX idx_admin_cat_segment     ON ecm_admin.document_categories(segment_id);
CREATE INDEX idx_admin_cat_product_line ON ecm_admin.document_categories(product_line_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Products  (includes segment / product_line from V2)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.products (
                                    id              SERIAL       PRIMARY KEY,
                                    product_code    VARCHAR(50)  NOT NULL UNIQUE,
                                    display_name    VARCHAR(200) NOT NULL,
                                    description     TEXT,
                                    product_schema  JSONB,
                                    segment_id      INTEGER      REFERENCES ecm_admin.segments(id),
                                    product_line_id INTEGER      REFERENCES ecm_admin.product_lines(id),
                                    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
                                    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_product_code        ON ecm_admin.products(product_code);
CREATE INDEX idx_admin_product_active      ON ecm_admin.products(is_active);
CREATE INDEX idx_admin_product_segment     ON ecm_admin.products(segment_id);
CREATE INDEX idx_admin_product_line        ON ecm_admin.products(product_line_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Product ↔ Category links
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
-- External Product References  (from V2 — third-party system IDs)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.external_product_refs (
                                                 id              SERIAL       PRIMARY KEY,
                                                 product_id      INTEGER      NOT NULL REFERENCES ecm_admin.products(id) ON DELETE CASCADE,
                                                 external_system VARCHAR(50)  NOT NULL,   -- 'BLOOMBERG', 'MORNINGSTAR', 'CORE_BANKING'
                                                 external_id     VARCHAR(200) NOT NULL,
                                                 sync_at         TIMESTAMPTZ,
                                                 UNIQUE (product_id, external_system)
);
CREATE INDEX idx_extref_product ON ecm_admin.external_product_refs(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Retention Policies  (includes segment/product_line/priority from V3)
-- Resolution order (lower priority wins):
--   document_type(10) → category(20) → product(30) → product_line(50) → segment(80) → default(100)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.retention_policies (
                                              id                  SERIAL       PRIMARY KEY,
                                              name                VARCHAR(200) NOT NULL,
                                              category_id         INTEGER,                   -- soft ref: ecm_admin.document_categories.id
                                              product_code        VARCHAR(50),               -- soft ref: ecm_admin.products.product_code
                                              segment_id          INTEGER      REFERENCES ecm_admin.segments(id),
                                              product_line_id     INTEGER      REFERENCES ecm_admin.product_lines(id),
                                              archive_after_days  INTEGER      NOT NULL DEFAULT 365,
                                              purge_after_days    INTEGER      NOT NULL DEFAULT 2555,
                                              priority            INTEGER      NOT NULL DEFAULT 100,
                                              is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
                                              created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                              updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_rp_category    ON ecm_admin.retention_policies(category_id);
CREATE INDEX idx_admin_rp_segment     ON ecm_admin.retention_policies(segment_id);
CREATE INDEX idx_admin_rp_active      ON ecm_admin.retention_policies(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Tenant Configuration  (white-label / branding key-value store)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.tenant_config (
                                         key         VARCHAR(100) PRIMARY KEY,
                                         value       TEXT         NOT NULL,
                                         description VARCHAR(500),
                                         updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Integration Configurations  (typed credential store per integration)
--
-- Replaces the ad-hoc tenant_config keys for external systems.
-- config  — non-sensitive fields stored as plain JSONB
-- secrets — sensitive fields stored AES-encrypted (prefix: aes:{base64})
--           Master key injected via MASTER_ENCRYPT_KEY env variable.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.integration_configs (
                                               id           SERIAL       PRIMARY KEY,
                                               tenant_id    VARCHAR(100) NOT NULL DEFAULT 'default',
                                               system_key   VARCHAR(50)  NOT NULL,                   -- 'DOCUSIGN' | 'AZURE_AI' | 'CORE_BANKING'
                                               display_name VARCHAR(100) NOT NULL DEFAULT '',        -- Human-readable name shown in admin UI
                                               config       JSONB        NOT NULL DEFAULT '{}'::jsonb,
                                               secrets      JSONB        NOT NULL DEFAULT '{}'::jsonb, -- AES-encrypted sensitive fields
                                               enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
                                               tested_at    TIMESTAMPTZ,
                                               test_status  VARCHAR(20)  NOT NULL DEFAULT 'UNTESTED', -- OK | FAILED | UNTESTED
                                               created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                               updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                               UNIQUE (tenant_id, system_key)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.departments — VIEW over ecm_core.departments
-- Allows ecm-admin JPA entities to resolve departments without cross-schema joins.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE VIEW ecm_admin.departments AS
SELECT id, name, code, parent_id, is_active, created_at, updated_at
FROM ecm_core.departments;


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_WORKFLOW — Flowable BPM bridge + template engine + SLA + task history
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Groups
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_groups (
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
CREATE TABLE ecm_workflow.workflow_group_members (
                                                     id       SERIAL  PRIMARY KEY,
                                                     group_id INTEGER NOT NULL REFERENCES ecm_workflow.workflow_groups(id) ON DELETE CASCADE,
                                                     user_id  INTEGER NOT NULL,                  -- soft ref: ecm_core.users.id
                                                     added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                                     UNIQUE (group_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Definition Configs  (named Flowable process configs)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_definition_configs (
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
CREATE TABLE ecm_workflow.category_workflow_mappings (
                                                         id                     SERIAL  PRIMARY KEY,
                                                         category_id            INTEGER NOT NULL UNIQUE,   -- soft ref: ecm_core.document_categories.id
                                                         workflow_definition_id INTEGER NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
                                                         is_active              BOOLEAN NOT NULL DEFAULT TRUE,
                                                         created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Instance Records
--
-- status: VARCHAR(30) not PostgreSQL ENUM — avoids ALTER TYPE migrations.
-- Valid: ACTIVE | INFO_REQUESTED | COMPLETED_APPROVED | COMPLETED_REJECTED | CANCELLED
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_instance_records (
                                                        id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                                        process_instance_id    VARCHAR(100) NOT NULL UNIQUE,
                                                        document_id            UUID         NOT NULL,      -- soft ref: ecm_core.documents.id
                                                        document_name          VARCHAR(500),
                                                        category_id            INTEGER,
                                                        workflow_definition_id INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
                                                        status                 VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
                                                        trigger_type           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
                                                        started_by_subject     VARCHAR(255) NOT NULL,
                                                        started_by_email       VARCHAR(255),
                                                        template_id            INTEGER,                    -- soft ref: workflow_templates.id
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
-- Workflow Templates  (low-code DSL + optional visual BPMN XML)
--
-- bpmn_source:
--   'DSL'    — BPMN generated at publish time from dsl_definition JSON
--   'VISUAL' — raw BPMN XML authored in bpmn.io designer; stored in bpmn_xml
--              Takes precedence over dsl_definition when publishing.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_templates (
                                                 id                      SERIAL       PRIMARY KEY,
                                                 name                    VARCHAR(200) NOT NULL,
                                                 description             TEXT,
                                                 process_key             VARCHAR(200) UNIQUE,
                                                 dsl_definition          JSONB        NOT NULL,
                                                 bpmn_xml                TEXT,                   -- raw BPMN 2.0 XML from bpmn.io (from V2 migration)
                                                 bpmn_source             VARCHAR(20)  NOT NULL DEFAULT 'DSL',  -- DSL | VISUAL
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
-- Workflow Template Mappings  (hierarchy-aware: segment + product_line + category → template)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_template_mappings (
                                                         id              SERIAL   PRIMARY KEY,
                                                         template_id     INTEGER  NOT NULL REFERENCES ecm_workflow.workflow_templates(id),
                                                         segment_id      INTEGER,                  -- soft ref: ecm_admin.segments.id
                                                         product_line_id INTEGER,                  -- soft ref: ecm_admin.product_lines.id
                                                         category_id     INTEGER  NOT NULL,        -- soft ref: ecm_admin.document_categories.id
                                                         priority        INTEGER  NOT NULL DEFAULT 100,
                                                         is_active       BOOLEAN  NOT NULL DEFAULT TRUE,
                                                         created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                                                         CONSTRAINT uq_template_mapping UNIQUE (segment_id, product_line_id, category_id)
);
CREATE INDEX idx_wtm_category       ON ecm_workflow.workflow_template_mappings(category_id);
CREATE INDEX idx_wtm_product_line   ON ecm_workflow.workflow_template_mappings(product_line_id);
CREATE INDEX idx_wtm_segment        ON ecm_workflow.workflow_template_mappings(segment_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow SLA Tracking
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_sla_tracking (
                                                    id                   SERIAL    PRIMARY KEY,
                                                    workflow_instance_id UUID      NOT NULL UNIQUE
                                                        REFERENCES ecm_workflow.workflow_instance_records(id) ON DELETE CASCADE,
                                                    template_id          INTEGER   REFERENCES ecm_workflow.workflow_templates(id),
                                                    sla_deadline         TIMESTAMP NOT NULL,
                                                    warning_threshold_at TIMESTAMP NOT NULL,
                                                    escalation_deadline  TIMESTAMP,
                                                    status               VARCHAR(30) NOT NULL DEFAULT 'ON_TRACK',  -- ON_TRACK|WARNING|ESCALATED|BREACHED|COMPLETED
                                                    warning_sent_at      TIMESTAMP,
                                                    escalated_at         TIMESTAMP,
                                                    breached_at          TIMESTAMP,
                                                    completed_at         TIMESTAMP,
                                                    created_at           TIMESTAMP NOT NULL DEFAULT NOW(),
                                                    updated_at           TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sla_status   ON ecm_workflow.workflow_sla_tracking(status);
CREATE INDEX idx_sla_deadline ON ecm_workflow.workflow_sla_tracking(sla_deadline);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Task History  (immutable per-action audit trail)
--
-- Records every task action (claim, release, approve, reject, info-request).
-- One row per action — never updated, only inserted.
-- ─────────────────────────────────────────────────────────────────────────────
-- Columns aligned with WorkflowTaskHistory.java entity and V2__sprint2_task_history.sql
CREATE TABLE ecm_workflow.workflow_task_history (
                                                    id                  BIGSERIAL    PRIMARY KEY,
                                                    task_id             VARCHAR(64)  NOT NULL,            -- Flowable task ID
                                                    process_instance_id VARCHAR(64)  NOT NULL,            -- Flowable process instance ID (string, not FK)
                                                    document_id         UUID,                             -- ECM document being reviewed (nullable for non-doc flows)
                                                    action              VARCHAR(30)  NOT NULL,            -- CLAIMED|RELEASED|APPROVED|REJECTED|INFO_REQUESTED|INFO_PROVIDED
                                                    actor_subject       VARCHAR(200) NOT NULL,            -- Entra Object ID / Okta sub
                                                    actor_email         VARCHAR(200),
                                                    comment             TEXT,
                                                    sla_deadline        TIMESTAMPTZ,
                                                    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wth_task_id    ON ecm_workflow.workflow_task_history(task_id);
CREATE INDEX idx_wth_process_id ON ecm_workflow.workflow_task_history(process_instance_id);
CREATE INDEX idx_wth_actor      ON ecm_workflow.workflow_task_history(actor_subject);
CREATE INDEX idx_wth_created_at ON ecm_workflow.workflow_task_history(created_at DESC);


-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM_FORMS — low-code eForms engine
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Product Type Catalogue
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
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_definitions (
                                            id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                            tenant_id            VARCHAR(100) NOT NULL DEFAULT 'default',
                                            form_key             VARCHAR(200) NOT NULL,
                                            name                 VARCHAR(255) NOT NULL,
                                            description          TEXT,
                                            product_type_code    VARCHAR(100),
                                            form_type_code       VARCHAR(100),
                                            version              INTEGER      NOT NULL DEFAULT 1,
                                            tags                 TEXT[],
                                            status               VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
                                            schema               JSONB,
                                            ui_config            JSONB,
                                            workflow_config      JSONB,
                                            docusign_config      JSONB,
                                            document_template_id UUID,
                                            published_at         TIMESTAMPTZ,
                                            published_by         VARCHAR(255),
                                            archived_at          TIMESTAMPTZ,
                                            archived_by          VARCHAR(255),
                                            created_by           VARCHAR(255) NOT NULL,
                                            updated_by           VARCHAR(255),
                                            created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                            updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                            CONSTRAINT uq_form_def_version UNIQUE (tenant_id, form_key, version)
);
-- Enforce: only ONE published version per (tenant, form_key)
CREATE UNIQUE INDEX idx_uq_published_form ON ecm_forms.form_definitions (tenant_id, form_key)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_form_def_tenant       ON ecm_forms.form_definitions(tenant_id);
CREATE INDEX idx_form_def_form_key     ON ecm_forms.form_definitions(form_key);
CREATE INDEX idx_form_def_status       ON ecm_forms.form_definitions(status);
CREATE INDEX idx_form_def_product_type ON ecm_forms.form_definitions(product_type_code);

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Submissions
--
-- party_id / party_display_name: added Sprint 1 — links submission to a customer.
--   party_id is a soft ref (UUID). Written by ecm-eforms when form is submitted.
--   party_display_name is denormalised for display without a join.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_submissions (
                                            id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                            tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
                                            form_definition_id    UUID         NOT NULL REFERENCES ecm_forms.form_definitions(id),
                                            form_key              VARCHAR(200) NOT NULL,
                                            form_version          INTEGER      NOT NULL,
                                            form_schema_snapshot  JSONB,
                                            submission_data       JSONB,
    -- Party context (Sprint 1)
                                            party_id              UUID,                         -- soft ref → ecm_core.parties.id
                                            party_display_name    VARCHAR(255),                 -- denormalised
    -- Lifecycle
    -- DRAFT|SUBMITTED|PENDING_SIGNATURE|SIGNED|SIGN_DECLINED
    -- IN_REVIEW|APPROVED|REJECTED|COMPLETED|WITHDRAWN
                                            status                VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
                                            submitted_by          VARCHAR(255) NOT NULL,
                                            submitted_by_name     VARCHAR(255),
                                            submitted_at          TIMESTAMPTZ,
    -- DocuSign
                                            docusign_envelope_id  VARCHAR(255),
                                            docusign_status       VARCHAR(100),
                                            docusign_sent_at      TIMESTAMPTZ,
                                            docusign_completed_at TIMESTAMPTZ,
                                            signed_document_id    UUID,
                                            draft_document_id     UUID,
    -- Workflow bridge
                                            workflow_instance_id  VARCHAR(255),
    -- Backoffice review
                                            assigned_to           VARCHAR(255),
                                            assigned_at           TIMESTAMPTZ,
                                            review_notes          TEXT,
                                            reviewed_by           VARCHAR(255),
                                            reviewed_at           TIMESTAMPTZ,
    -- Request metadata
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
CREATE INDEX idx_sub_party         ON ecm_forms.form_submissions(party_id);
CREATE INDEX idx_sub_data_gin      ON ecm_forms.form_submissions USING GIN (submission_data);

-- ─────────────────────────────────────────────────────────────────────────────
-- DocuSign Webhook Event Log
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
CREATE INDEX idx_ds_envelope  ON ecm_forms.docusign_events(envelope_id);
CREATE INDEX idx_ds_processed ON ecm_forms.docusign_events(processed);


-- ═══════════════════════════════════════════════════════════════════════════════
-- SEED DATA
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Roles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.roles (name, description) VALUES
                                                   ('ECM_ADMIN',      'Full system administration access'),
                                                   ('ECM_DESIGNER',   'Can create and publish eForms and workflow templates'),
                                                   ('ECM_BACKOFFICE', 'Standard back-office document and workflow access'),
                                                   ('ECM_REVIEWER',   'Can review and approve workflow tasks'),
                                                   ('ECM_READONLY',   'Read-only access to assigned departments')
    ON CONFLICT (name) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Departments
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
-- ecm_core: Document Categories (lightweight lookup)
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
-- ecm_admin: Segments
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.segments (name, code, description) VALUES
                                                             ('Retail',         'RETAIL',     'Retail banking — individuals and households'),
                                                             ('Commercial',     'COMMERCIAL', 'Commercial banking — mid-market and enterprise'),
                                                             ('Small Business', 'SMB',        'Small business banking — sole traders and small enterprises')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Product Lines
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.product_lines (segment_id, name, code, description) VALUES
                                                                              (1, 'Banking',      'RETAIL_BANKING',      'Retail current, savings, and chequing accounts'),
                                                                              (1, 'Loans',        'RETAIL_LOANS',        'Retail mortgages, auto loans, personal loans'),
                                                                              (1, 'Investment',   'RETAIL_INVESTMENT',   'Retail term deposits and investment accounts'),
                                                                              (1, 'Mutual Funds', 'RETAIL_MUTUAL_FUNDS', 'Third-party mutual fund distribution'),
                                                                              (2, 'Banking',      'COMM_BANKING',        'Commercial transactional accounts'),
                                                                              (2, 'Lending',      'COMM_LENDING',        'Commercial credit facilities and trade finance'),
                                                                              (3, 'Banking',      'SMB_BANKING',         'Small business accounts'),
                                                                              (3, 'Loans',        'SMB_LOANS',           'Small business lending')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Document Categories (admin-enriched)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.document_categories (name, code, description) VALUES
                                                                        ('Mortgage',              'MORTGAGE',  'Mortgage application documents'),
                                                                        ('Auto Loan',             'AUTO_LOAN', 'Auto loan application documents'),
                                                                        ('Identity Verification', 'IDENTITY',  'KYC and identity documents'),
                                                                        ('Financial Statements',  'FINANCIAL', 'Income, tax and financial records'),
                                                                        ('Legal Agreements',      'LEGAL',     'Signed legal and compliance documents'),
                                                                        ('Invoice',               'INV',       'Vendor and customer invoices'),
                                                                        ('Contract',              'CTR',       'Legal contracts and agreements'),
                                                                        ('HR Document',           'HRD',       'HR records and payslips'),
                                                                        ('Scanned Form',          'SCF',       'Scanned physical forms'),
                                                                        ('Compliance',            'COMPLIANCE','AML, KYC and regulatory compliance documents')
    ON CONFLICT (code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Products
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.products (product_code, display_name, description, segment_id, product_line_id, product_schema) VALUES
                                                                                                                          ('MORTGAGE',
                                                                                                                           'Mortgage Application', 'Residential mortgage origination',
                                                                                                                           1, 2,
                                                                                                                           '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"propertyAddress","label":"Property Address","type":"text","required":true}]}'::jsonb),
                                                                                                                          ('AUTO_LOAN',
                                                                                                                           'Auto Loan Application', 'Vehicle purchase and refinance loans',
                                                                                                                           1, 2,
                                                                                                                           '{"fields":[{"key":"vehicleVin","label":"Vehicle VIN","type":"text","required":true},{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true}]}'::jsonb),
                                                                                                                          ('PERSONAL_LOAN',
                                                                                                                           'Personal Loan', 'Unsecured personal lending',
                                                                                                                           1, 2,
                                                                                                                           '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"purpose","label":"Loan Purpose","type":"text","required":true}]}'::jsonb),
                                                                                                                          ('COMM_CREDIT_FACILITY',
                                                                                                                           'Commercial Credit Facility', 'Commercial revolving credit and term loans',
                                                                                                                           2, 6,
                                                                                                                           '{"fields":[{"key":"facilityAmount","label":"Facility Amount","type":"currency","required":true},{"key":"businessRegNo","label":"Registration No","type":"text","required":true}]}'::jsonb),
                                                                                                                          ('SMB_LOAN',
                                                                                                                           'Small Business Loan', 'Small business lending products',
                                                                                                                           3, 8,
                                                                                                                           '{"fields":[{"key":"loanAmount","label":"Loan Amount","type":"currency","required":true},{"key":"businessName","label":"Business Name","type":"text","required":true}]}'::jsonb)
    ON CONFLICT (product_code) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Tenant Config (white-label defaults)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.tenant_config (key, value, description) VALUES
                                                                  ('tenant.name',          'ECM Platform', 'Organisation display name'),
                                                                  ('tenant.logo_url',      '',             'Logo URL for header branding'),
                                                                  ('tenant.primary_color', '#002347',      'Brand primary colour (hex)'),
                                                                  ('tenant.support_email', '',             'Support email shown in UI footer'),
                                                                  ('tenant.timezone',      'UTC',          'Default timezone for date display'),
                                                                  ('webhook.document_indexed.url',    '',  'POST callback URL when document reaches INDEXED status'),
                                                                  ('webhook.submission_signed.url',   '',  'POST callback URL when DocuSign signing is confirmed')
    ON CONFLICT (key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Integration Configs (seed with disabled DocuSign stub)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.integration_configs (tenant_id, system_key, display_name, config, enabled) VALUES
                                                                                                     ('default', 'DOCUSIGN', 'DocuSign eSignature',
                                                                                                      '{"base_url":"https://demo.docusign.net","auth_server":"https://account-d.docusign.com","account_id":"","integration_key":"","impersonated_user_id":""}'::jsonb,
                                                                                                      false),
                                                                                                     ('default', 'AZURE_AI', 'Azure AI Document Intelligence',
                                                                                                      '{"endpoint":"","api_version":"2024-02-29-preview"}'::jsonb,
                                                                                                      false)
    ON CONFLICT (tenant_id, system_key) DO NOTHING;

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
     'Two-step: backoffice triage then underwriter approval.',
     'document-dual-review', 'ECM_REVIEWER', TRUE, 24),
    ('Compliance Review',
     'Compliance team single-step review. Used for KYC and regulatory documents.',
     'document-compliance-review', 'ECM_REVIEWER', TRUE, 24)
    ON CONFLICT DO NOTHING;


-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms: Product Types
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_forms.product_types (tenant_id, code, label, description, icon, sort_order) VALUES
                                                                                                ('default', 'MORTGAGE',        'Mortgage',         'Residential & commercial mortgage products',  'home',         1),
                                                                                                ('default', 'AUTO_LOAN',       'Auto Loan',        'Vehicle financing products',                  'car',          2),
                                                                                                ('default', 'PERSONAL_LOAN',   'Personal Loan',    'Unsecured personal lending',                  'user',         3),
                                                                                                ('default', 'CREDIT_CARD',     'Credit Card',      'Credit card applications and amendments',     'credit-card',  4),
                                                                                                ('default', 'INSURANCE',       'Insurance',        'Life, property and liability insurance',      'shield',       5),
                                                                                                ('default', 'INVESTMENT',      'Investment',       'Investment accounts and products',            'trending-up',  6),
                                                                                                ('default', 'ACCOUNT_OPENING', 'Account Opening',  'Personal and business account opening',       'briefcase',    7),
                                                                                                ('default', 'BUSINESS_LOAN',   'Business Loan',    'Commercial lending products',                 'building',     8),
                                                                                                ('default', 'COMPLIANCE',      'Compliance',       'AML, KYC and regulatory compliance forms',   'check-square', 9)
    ON CONFLICT ON CONSTRAINT uq_product_type DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms: Form Types
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_forms.form_types (tenant_id, code, label, description, sort_order) VALUES
                                                                                       ('default', 'APPLICATION', 'Application',  'New product or account applications',          1),
                                                                                       ('default', 'KYC',         'KYC',          'Know Your Customer identity verification',     2),
                                                                                       ('default', 'AML',         'AML',          'Anti-Money Laundering disclosure',             3),
                                                                                       ('default', 'DISCLOSURE',  'Disclosure',   'Regulatory and product disclosures',           4),
                                                                                       ('default', 'CONSENT',     'Consent',      'Customer consent and authorisation',           5),
                                                                                       ('default', 'AMENDMENT',   'Amendment',    'Amendment to existing product or account',     6),
                                                                                       ('default', 'CLAIM',       'Claim',        'Insurance or dispute claim forms',             7),
                                                                                       ('default', 'RENEWAL',     'Renewal',      'Product renewal and extension',                8),
                                                                                       ('default', 'ONBOARDING',  'Onboarding',   'Employee or customer onboarding forms',        9),
                                                                                       ('default', 'OTHER',       'Other',        'General purpose forms',                       10)
    ON CONFLICT ON CONSTRAINT uq_form_type DO NOTHING;


-- ═══════════════════════════════════════════════════════════════════════════════
-- Schema update
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE ecm_workflow.workflow_template_mappings
    ADD COLUMN IF NOT EXISTS product_id INTEGER;

COMMENT ON COLUMN ecm_workflow.workflow_template_mappings.product_id
    IS 'Soft FK → ecm_admin.products.id. NULL = mapping applies to all products in the category.';

-- ═══════════════════════════════════════════════════════════════════════════════
-- POST-APPLY CHECKLIST
-- ═══════════════════════════════════════════════════════════════════════════════

-- Index for lookups by product
CREATE INDEX IF NOT EXISTS idx_wtm_product_id
    ON ecm_workflow.workflow_template_mappings (product_id);

--
-- 1. DELETE all V*.sql files from:
--      ecm-admin/src/main/resources/db/migration/
--      ecm-document/src/main/resources/db/migration/
--      ecm-workflow/src/main/resources/db/migration/
--      ecm-eforms/src/main/resources/db/migration/   (if any)
--
-- 2. Add a V1__baseline.sql to each module (content: "-- baseline"):
--      This lets Flyway create its flyway_schema_history table and
--      register version 1 as the starting point. No schema changes.
--
-- 3. Update Document.java entity to add:
--      @Column(name = "party_id") private UUID partyId;
--
-- 4. Update FormSubmission.java entity to add:
--      @Column(name = "party_id") private UUID partyId;
--      @Column(name = "party_display_name") private String partyDisplayName;
--
-- 5. Add ecm-tesseract service to docker-compose.yml (see Sprint 1 handoff).
--
-- 6. Start services — Flyway finds no pending migrations and starts cleanly.
-- ═══════════════════════════════════════════════════════════════════════════════