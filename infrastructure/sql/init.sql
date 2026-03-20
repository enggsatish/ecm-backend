-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM Platform — Master Database Initialisation  v4.0
--
-- REPLACES: infrastructure/sql/init.sql  (v3.0)
-- FOLDS IN all previous migrations (V1–V7) from every module.
--
-- NEW in v4.0 (Enterprise Refactor):
--   ecm_core.cases                       — case / application container
--   ecm_core.case_documents              — checklist tracking per case
--   ecm_core.external_participants       — external party support (schema only, v2)
--   ecm_admin.product_document_types     — replaces product_category_links
--   ecm_admin.ocr_templates              — DB-stored OCR extraction templates
--   ecm_admin.products.case_workflow_key — Flowable processKey for case workflow
--
-- REMOVED in v4.0:
--   ecm_forms.product_types              — redundant with ecm_admin.products
--   ecm_forms.form_types                 — simplified to column/enum
--   ecm_admin.product_category_links     — replaced by product_document_types
--   ecm_workflow.category_workflow_mappings   — workflow key on product_document_types
--   ecm_workflow.workflow_template_mappings   — redundant
--   ecm_admin.document_categories.segment_id       — dead weight
--   ecm_admin.document_categories.product_line_id  — dead weight
--   ecm_forms.form_definitions.product_type_code   — redundant
--   ecm_forms.form_definitions.form_type_code      — redundant
--
-- HOW TO APPLY (clean start):
--   docker compose down -v
--   docker compose up -d   (postgres runs this file via /docker-entrypoint-initdb.d/)
--
-- AFTER APPLYING:
--   1. Delete ALL V*.sql files from every module's db/migration/ directory.
--   2. Add a single V1__baseline.sql (content: "-- baseline") to each module.
--   3. Future schema changes go into V2__*.sql in the relevant module.
--
-- Schema layout:
--   ecm_core     — shared domain: users, parties, cases, documents
--   ecm_audit    — immutable audit trail
--   ecm_admin    — product catalogue, segments, tenant config, integrations, OCR templates
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
    is_system   BOOLEAN     NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Users
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.users (
    id               SERIAL       PRIMARY KEY,
    entra_object_id  VARCHAR(255) UNIQUE,           -- NULL until first SSO login binds the real subject
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
-- RBAC: Modules, Permissions, Bundles  (folded from ecm-identity V5)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.modules (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE ecm_core.permissions (
    id          SERIAL       PRIMARY KEY,
    module_code VARCHAR(50)  NOT NULL REFERENCES ecm_core.modules(code),
    action      VARCHAR(50)  NOT NULL,
    code        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (module_code, action)
);

CREATE TABLE ecm_core.role_permissions (
    role_id       INTEGER      NOT NULL REFERENCES ecm_core.roles(id),
    permission_id INTEGER      NOT NULL REFERENCES ecm_core.permissions(id),
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by    VARCHAR(255),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE ecm_core.capability_bundles (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE ecm_core.bundle_permissions (
    bundle_id     INTEGER NOT NULL REFERENCES ecm_core.capability_bundles(id),
    permission_id INTEGER NOT NULL REFERENCES ecm_core.permissions(id),
    PRIMARY KEY (bundle_id, permission_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Parties  (Customer / Client entity)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.parties (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id      VARCHAR(100) NOT NULL UNIQUE,
    party_type       VARCHAR(20)  NOT NULL,
    segment_id       INTEGER      NOT NULL,          -- soft ref → ecm_admin.segments.id
    display_name     VARCHAR(255) NOT NULL,
    short_name       VARCHAR(100),
    registration_no  VARCHAR(100),
    parent_party_id  UUID         REFERENCES ecm_core.parties(id) ON DELETE RESTRICT,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    notes            TEXT,
    created_by       VARCHAR(255) NOT NULL,
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
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.documents (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(500)  NOT NULL,
    original_filename   VARCHAR(500)  NOT NULL,
    mime_type           VARCHAR(100)  NOT NULL,
    file_size_bytes     BIGINT,
    blob_storage_path   VARCHAR(1000) NOT NULL,
    category_id         INTEGER,                   -- soft ref: ecm_admin.document_categories.id
    department_id       INTEGER       REFERENCES ecm_core.departments(id) ON DELETE SET NULL,
    uploaded_by         INTEGER       REFERENCES ecm_core.users(id) ON DELETE SET NULL,
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
    -- Party link
    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
    party_external_id   VARCHAR(100), -- denormalised soft ref to parties.external_id
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
CREATE INDEX idx_core_docs_party_ext    ON ecm_core.documents(party_external_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Cases  (Loan Application / Account Opening / etc.)
--
-- Groups all documents and workflows for a single business process.
-- Created by: ECM user, LOS via API, or Customer via online banking.
-- Status lifecycle:
--   OPEN → DOCUMENTS_PENDING → UNDER_REVIEW → WITH_EXTERNAL
--     → PENDING_APPROVAL → APPROVED → FUNDING → COMPLETED
--   (also: REJECTED, CANCELLED, ON_HOLD at any point)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.cases (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref        VARCHAR(200)  UNIQUE,            -- LOS/CRM reference e.g. "LOAN-2026-00142"
    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
    product_id          INTEGER,                          -- soft ref → ecm_admin.products.id
    case_type           VARCHAR(50),                      -- LOAN_ORIGINATION, ACCOUNT_OPENING, etc.
    status              VARCHAR(50)   NOT NULL DEFAULT 'OPEN',
    assigned_to         VARCHAR(255),                     -- primary FA/case owner (Okta subject)
    assigned_to_name    VARCHAR(255),
    source_system       VARCHAR(50)   NOT NULL DEFAULT 'ECM',  -- ECM | LOS | ONLINE_BANKING | CRM
    source_ref          VARCHAR(200),                     -- originating system's internal reference
    process_instance_id VARCHAR(200),                     -- Flowable process instance driving this case
    metadata            JSONB,
    opened_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_case_party       ON ecm_core.cases(party_id);
CREATE INDEX idx_case_product     ON ecm_core.cases(product_id);
CREATE INDEX idx_case_status      ON ecm_core.cases(status);
CREATE INDEX idx_case_assigned    ON ecm_core.cases(assigned_to);
CREATE INDEX idx_case_source      ON ecm_core.cases(source_system);
CREATE INDEX idx_case_external    ON ecm_core.cases(external_ref);
CREATE INDEX idx_case_created     ON ecm_core.cases(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Case Documents  (Checklist tracking — one row per required/optional doc type)
--
-- Auto-populated from product_document_types when a case is created.
-- document_id is NULL until a document is uploaded for this checklist item.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_documents (
    id                       SERIAL       PRIMARY KEY,
    case_id                  UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    product_document_type_id INTEGER      NOT NULL,  -- soft ref → ecm_admin.product_document_types.id
    document_id              UUID,                    -- soft ref → ecm_core.documents.id (NULL until uploaded)
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    -- PENDING | UPLOADED | UNDER_REVIEW | APPROVED | REJECTED | WAIVED
    uploaded_by              VARCHAR(255),
    uploaded_at              TIMESTAMPTZ,
    reviewed_by              VARCHAR(255),
    reviewed_at              TIMESTAMPTZ,
    review_notes             TEXT,
    waived_by                VARCHAR(255),
    waived_reason            TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cdoc_case     ON ecm_core.case_documents(case_id);
CREATE INDEX idx_cdoc_doc      ON ecm_core.case_documents(document_id);
CREATE INDEX idx_cdoc_status   ON ecm_core.case_documents(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- External Participants  (Lawyers, Appraisers, Notaries — v2 app code)
--
-- Schema created now; application code in a future release.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.external_participants (
    id               SERIAL       PRIMARY KEY,
    case_id          UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    organization     VARCHAR(200),
    role             VARCHAR(30)  NOT NULL,  -- LAWYER | APPRAISER | NOTARY | INSURER
    access_token     VARCHAR(500),
    token_expires_at TIMESTAMPTZ,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ext_part_case  ON ecm_core.external_participants(case_id);
CREATE INDEX idx_ext_part_email ON ecm_core.external_participants(email);

-- ─────────────────────────────────────────────────────────────────────────────
-- Notifications  (in-app notification inbox)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.notifications (
    id              BIGSERIAL    PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,          -- Okta subject / email of the recipient
    title           VARCHAR(300) NOT NULL,
    body            TEXT,
    link            VARCHAR(500),                   -- optional URL to navigate to
    category        VARCHAR(50)  DEFAULT 'GENERAL', -- TASK_ASSIGNED, FORM_APPROVED, FORM_REJECTED, SYSTEM
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notif_recipient ON ecm_core.notifications(recipient, is_read);
CREATE INDEX idx_notif_created   ON ecm_core.notifications(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Email Templates  (admin-editable HTML templates for email notifications)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.email_templates (
    id               SERIAL       PRIMARY KEY,
    template_key     VARCHAR(50)  NOT NULL UNIQUE,
    name             VARCHAR(200) NOT NULL,
    subject_template VARCHAR(500) NOT NULL,
    body_template    TEXT         NOT NULL,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Email Queue  (batch outgoing emails — processed every 5 minutes)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.email_queue (
    id              BIGSERIAL    PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(500),
    body            TEXT,
    category        VARCHAR(50),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX idx_eq_status    ON ecm_core.email_queue(status);
CREATE INDEX idx_eq_recipient ON ecm_core.email_queue(recipient, status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Notification Preferences  (per-user opt-in/out per channel per category)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.notification_preferences (
    id          SERIAL       PRIMARY KEY,
    user_email  VARCHAR(255) NOT NULL,
    category    VARCHAR(50)  NOT NULL,
    channel     VARCHAR(20)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (user_email, category, channel)
);
CREATE INDEX idx_np_user ON ecm_core.notification_preferences(user_email);


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
-- Segments
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.segments (
    id          SERIAL       PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_seg_active ON ecm_admin.segments(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Product Lines
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
-- Document Categories  (universal — not tied to segment/product line)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.document_categories (
    id              SERIAL       PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    code            VARCHAR(100) NOT NULL UNIQUE,
    parent_id       INTEGER      REFERENCES ecm_admin.document_categories(id) ON DELETE RESTRICT,
    description     TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_cat_parent ON ecm_admin.document_categories(parent_id);
CREATE INDEX idx_admin_cat_active ON ecm_admin.document_categories(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Products
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.products (
    id                SERIAL       PRIMARY KEY,
    product_code      VARCHAR(50)  NOT NULL UNIQUE,
    display_name      VARCHAR(200) NOT NULL,
    description       TEXT,
    product_schema    JSONB,
    segment_id        INTEGER      REFERENCES ecm_admin.segments(id),
    product_line_id   INTEGER      REFERENCES ecm_admin.product_lines(id),
    case_workflow_key VARCHAR(100),            -- Flowable processKey for case-level workflow
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_product_code        ON ecm_admin.products(product_code);
CREATE INDEX idx_admin_product_active      ON ecm_admin.products(is_active);
CREATE INDEX idx_admin_product_segment     ON ecm_admin.products(segment_id);
CREATE INDEX idx_admin_product_line        ON ecm_admin.products(product_line_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Product Document Types  (document checklist per product)
--
-- Defines what documents each product requires or accepts.
-- source_type: EFORM (system generates via eForms) | UPLOAD (customer/user provides)
-- on_upload_action: OCR_ONLY (auto-approve) | REVIEW_REQUIRED (needs human review)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.product_document_types (
    id                 SERIAL       PRIMARY KEY,
    product_id         INTEGER      NOT NULL REFERENCES ecm_admin.products(id) ON DELETE CASCADE,
    category_id        INTEGER      NOT NULL REFERENCES ecm_admin.document_categories(id) ON DELETE CASCADE,
    name               VARCHAR(200) NOT NULL,
    code               VARCHAR(100) NOT NULL,
    source_type        VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD',
    form_definition_id UUID,                                    -- soft ref → ecm_forms.form_definitions.id
    on_upload_action   VARCHAR(30)  NOT NULL DEFAULT 'OCR_ONLY',
    is_required        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (product_id, code),
    CONSTRAINT ck_source_type      CHECK (source_type IN ('EFORM', 'UPLOAD')),
    CONSTRAINT ck_upload_action    CHECK (on_upload_action IN ('OCR_ONLY', 'REVIEW_REQUIRED'))
);
CREATE INDEX idx_pdt_product  ON ecm_admin.product_document_types(product_id);
CREATE INDEX idx_pdt_category ON ecm_admin.product_document_types(category_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- OCR Extraction Templates  (DB-stored, replaces classpath JSON files)
--
-- One template per document category.  Fields column stores the array of
-- extraction rules: [{fieldName, pattern, defaultValue}, ...]
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.ocr_templates (
    id            SERIAL       PRIMARY KEY,
    category_id   INTEGER      UNIQUE REFERENCES ecm_admin.document_categories(id),
    category_code VARCHAR(100) NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    fields        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- External Product References  (third-party system IDs)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.external_product_refs (
    id              SERIAL       PRIMARY KEY,
    product_id      INTEGER      NOT NULL REFERENCES ecm_admin.products(id) ON DELETE CASCADE,
    external_system VARCHAR(50)  NOT NULL,
    external_id     VARCHAR(200) NOT NULL,
    sync_at         TIMESTAMPTZ,
    UNIQUE (product_id, external_system)
);
CREATE INDEX idx_extref_product ON ecm_admin.external_product_refs(product_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Retention Policies
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.retention_policies (
    id                  SERIAL       PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    category_id         INTEGER,
    product_code        VARCHAR(50),
    segment_id          INTEGER      REFERENCES ecm_admin.segments(id),
    product_line_id     INTEGER      REFERENCES ecm_admin.product_lines(id),
    archive_after_days  INTEGER      NOT NULL DEFAULT 365,
    purge_after_days    INTEGER      NOT NULL DEFAULT 2555,
    priority            INTEGER      NOT NULL DEFAULT 100,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_rp_category ON ecm_admin.retention_policies(category_id);
CREATE INDEX idx_admin_rp_segment  ON ecm_admin.retention_policies(segment_id);
CREATE INDEX idx_admin_rp_active   ON ecm_admin.retention_policies(is_active);

-- ─────────────────────────────────────────────────────────────────────────────
-- Tenant Configuration
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.tenant_config (
    key         VARCHAR(100) PRIMARY KEY,
    value       TEXT         NOT NULL,
    description VARCHAR(500),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Integration Configurations
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.integration_configs (
    id           SERIAL       PRIMARY KEY,
    tenant_id    VARCHAR(100) NOT NULL DEFAULT 'default',
    system_key   VARCHAR(50)  NOT NULL,
    display_name VARCHAR(100) NOT NULL DEFAULT '',
    config       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    secrets      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    tested_at    TIMESTAMPTZ,
    test_status  VARCHAR(20)  NOT NULL DEFAULT 'UNTESTED',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, system_key)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.departments — VIEW over ecm_core.departments
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
    user_id  INTEGER NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (group_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Definition Configs
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
-- Workflow Instance Records
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_instance_records (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    process_instance_id    VARCHAR(100) NOT NULL UNIQUE,
    document_id            UUID,                           -- soft ref: ecm_core.documents.id (nullable for form/case workflows)
    document_name          VARCHAR(500),
    category_id            INTEGER,
    workflow_definition_id INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
    status                 VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    trigger_type           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    started_by_subject     VARCHAR(255) NOT NULL,
    started_by_email       VARCHAR(255),
    template_id            INTEGER,
    submission_id          VARCHAR(100),                    -- form submission UUID (for form-triggered workflows)
    case_id                UUID,                            -- soft ref: ecm_core.cases.id (for case-triggered workflows)
    completed_at           TIMESTAMPTZ,
    final_comment          TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wir_document_id   ON ecm_workflow.workflow_instance_records(document_id);
CREATE INDEX idx_wir_status        ON ecm_workflow.workflow_instance_records(status);
CREATE INDEX idx_wir_started_by    ON ecm_workflow.workflow_instance_records(started_by_subject);
CREATE INDEX idx_wir_created_at    ON ecm_workflow.workflow_instance_records(created_at DESC);
CREATE INDEX idx_wir_submission_id ON ecm_workflow.workflow_instance_records(submission_id);
CREATE INDEX idx_wir_case_id       ON ecm_workflow.workflow_instance_records(case_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Templates
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_templates (
    id                      SERIAL       PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    description             TEXT,
    process_key             VARCHAR(200) UNIQUE,
    dsl_definition          JSONB        NOT NULL,
    bpmn_xml                TEXT,
    bpmn_source             VARCHAR(20)  NOT NULL DEFAULT 'DSL',
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

-- ─────────────────────────────────────────────────────────────────────────────
-- Workflow Task History
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_task_history (
    id                  BIGSERIAL    PRIMARY KEY,
    task_id             VARCHAR(64)  NOT NULL,
    process_instance_id VARCHAR(64)  NOT NULL,
    document_id         UUID,
    action              VARCHAR(30)  NOT NULL,
    actor_subject       VARCHAR(200) NOT NULL,
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
-- ECM_FORMS — eForms engine
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Definitions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_definitions (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            VARCHAR(100) NOT NULL DEFAULT 'default',
    form_key             VARCHAR(200) NOT NULL,
    name                 VARCHAR(255) NOT NULL,
    description          TEXT,
    version              INTEGER      NOT NULL DEFAULT 1,
    tags                 TEXT[],
    status               VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    schema               JSONB,
    ui_config            JSONB,
    workflow_config      JSONB,
    docusign_config      JSONB,
    document_template_id UUID,
    document_category_id INTEGER,     -- soft ref → ecm_admin.document_categories.id
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
CREATE UNIQUE INDEX idx_uq_published_form ON ecm_forms.form_definitions (tenant_id, form_key)
    WHERE status = 'PUBLISHED';
CREATE INDEX idx_form_def_tenant   ON ecm_forms.form_definitions(tenant_id);
CREATE INDEX idx_form_def_form_key ON ecm_forms.form_definitions(form_key);
CREATE INDEX idx_form_def_status   ON ecm_forms.form_definitions(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Form Submissions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_submissions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
    form_definition_id    UUID         NOT NULL REFERENCES ecm_forms.form_definitions(id),
    form_key              VARCHAR(200) NOT NULL,
    form_version          INTEGER      NOT NULL,
    form_schema_snapshot  JSONB,
    submission_data       JSONB,
    -- Party context
    party_id              UUID,
    party_display_name    VARCHAR(255),
    party_external_id     VARCHAR(100),
    -- Lifecycle
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
CREATE INDEX idx_sub_party_ext     ON ecm_forms.form_submissions(party_external_id);
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
INSERT INTO ecm_core.roles (name, description, is_system) VALUES
    ('ECM_ADMIN',      'Full system administration access',                    TRUE),
    ('ECM_DESIGNER',   'Can create and publish eForms and workflow templates', TRUE),
    ('ECM_BACKOFFICE', 'Standard back-office document and workflow access',    TRUE),
    ('ECM_REVIEWER',   'Can review and approve workflow tasks',                TRUE),
    ('ECM_READONLY',   'Read-only access to assigned departments',             TRUE)
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
-- ecm_core: RBAC Modules
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.modules (code, name, sort_order) VALUES
    ('DOCUMENTS', 'Document Management', 1),
    ('WORKFLOW',  'Workflow & Tasks',     2),
    ('EFORMS',    'Electronic Forms',     3),
    ('ADMIN',     'Administration',       4),
    ('OCR',       'OCR & Scanning',       5),
    ('ARCHIVE',   'Archive & Retention',  6);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: RBAC Permissions (24 total)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.permissions (module_code, action, code, description) VALUES
    ('DOCUMENTS', 'read',    'documents:read',    'View and download documents'),
    ('DOCUMENTS', 'write',   'documents:write',   'Edit document metadata'),
    ('DOCUMENTS', 'upload',  'documents:upload',  'Upload new documents'),
    ('DOCUMENTS', 'delete',  'documents:delete',  'Soft-delete documents'),
    ('DOCUMENTS', 'archive', 'documents:archive', 'Archive and restore documents'),
    ('DOCUMENTS', 'export',  'documents:export',  'Bulk export documents'),
    ('WORKFLOW',  'view',    'workflow:view',    'View workflow instances and tasks'),
    ('WORKFLOW',  'claim',   'workflow:claim',   'Claim unassigned tasks'),
    ('WORKFLOW',  'approve', 'workflow:approve', 'Approve workflow tasks'),
    ('WORKFLOW',  'reject',  'workflow:reject',  'Reject workflow tasks'),
    ('WORKFLOW',  'design',  'workflow:design',  'Create and edit workflow templates'),
    ('WORKFLOW',  'admin',   'workflow:admin',   'Manage all workflow instances'),
    ('EFORMS',    'submit',  'eforms:submit',  'Submit eForms'),
    ('EFORMS',    'review',  'eforms:review',  'Review eForm submissions'),
    ('EFORMS',    'design',  'eforms:design',  'Design eForm templates'),
    ('EFORMS',    'admin',   'eforms:admin',   'Manage all eForm definitions'),
    ('ADMIN',     'users',     'admin:users',     'Manage users and role assignments'),
    ('ADMIN',     'roles',     'admin:roles',     'Create and configure roles'),
    ('ADMIN',     'configure', 'admin:configure', 'System configuration and settings'),
    ('ADMIN',     'audit',     'admin:audit',     'View audit logs'),
    ('OCR',       'trigger', 'ocr:trigger', 'Trigger OCR processing'),
    ('OCR',       'view',    'ocr:view',    'View OCR results'),
    ('ARCHIVE',   'read',    'archive:read',   'View archived documents'),
    ('ARCHIVE',   'manage',  'archive:manage', 'Manage retention policies');

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Role → Permission grants
-- ─────────────────────────────────────────────────────────────────────────────

-- ECM_ADMIN: all 24 permissions
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r, ecm_core.permissions p
WHERE r.name = 'ECM_ADMIN';

-- ECM_BACKOFFICE
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read', 'documents:write', 'documents:upload',
    'workflow:view',  'workflow:claim',   'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'ocr:view',       'archive:read'
)
WHERE r.name = 'ECM_BACKOFFICE';

-- ECM_REVIEWER
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read',
    'workflow:view',  'workflow:approve', 'workflow:reject',
    'eforms:submit',  'eforms:review',
    'archive:read'
)
WHERE r.name = 'ECM_REVIEWER';

-- ECM_DESIGNER
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN (
    'documents:read',
    'workflow:view',   'workflow:design',
    'eforms:submit',   'eforms:design',
    'ocr:view'
)
WHERE r.name = 'ECM_DESIGNER';

-- ECM_READONLY
INSERT INTO ecm_core.role_permissions (role_id, permission_id, granted_by)
SELECT r.id, p.id, 'system'
FROM ecm_core.roles r
JOIN ecm_core.permissions p ON p.code IN ('documents:read', 'eforms:submit', 'archive:read')
WHERE r.name = 'ECM_READONLY';

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Capability Bundles
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.capability_bundles (code, name, description, sort_order) VALUES
    ('DOCUMENT_CONTRIBUTOR', 'Document Contributor',  'Upload, view, and manage documents',           1),
    ('TASK_PROCESSOR',       'Task Processor',        'Claim and action workflow tasks',               2),
    ('FORM_REVIEWER',        'Form Reviewer',         'Review and approve eForm submissions',          3),
    ('DESIGNER',             'Designer',              'Design workflows and eForms',                   4),
    ('COMPLIANCE_REVIEWER',  'Compliance Reviewer',   'Compliance audit, archive, export',             5);

-- Bundle → permission links
INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DOCUMENT_CONTRIBUTOR'
  AND p.code IN ('documents:read', 'documents:write', 'documents:upload', 'workflow:view', 'eforms:submit');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'TASK_PROCESSOR'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:claim', 'workflow:approve', 'workflow:reject', 'eforms:review', 'ocr:view');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'FORM_REVIEWER'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:approve', 'workflow:reject', 'eforms:submit', 'eforms:review');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'DESIGNER'
  AND p.code IN ('documents:read', 'workflow:view', 'workflow:design', 'eforms:submit', 'eforms:design', 'ocr:view');

INSERT INTO ecm_core.bundle_permissions (bundle_id, permission_id)
SELECT b.id, p.id FROM ecm_core.capability_bundles b, ecm_core.permissions p
WHERE b.code = 'COMPLIANCE_REVIEWER'
  AND p.code IN ('documents:read', 'documents:export', 'documents:archive', 'workflow:view', 'workflow:approve', 'workflow:reject', 'eforms:review', 'archive:read', 'archive:manage', 'admin:audit');

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
-- ecm_admin: Document Categories
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.document_categories (name, code, description) VALUES
    ('Mortgage',              'MORTGAGE',     'Mortgage application documents'),
    ('Auto Loan',             'AUTO_LOAN',    'Auto loan application documents'),
    ('Identity Verification', 'IDENTITY',     'KYC and identity documents'),
    ('Financial Statements',  'FINANCIAL',    'Income, tax and financial records'),
    ('Legal Agreements',      'LEGAL',        'Signed legal and compliance documents'),
    ('Invoice',               'INV',          'Vendor and customer invoices'),
    ('Contract',              'CTR',          'Legal contracts and agreements'),
    ('HR Document',           'HRD',          'HR records and payslips'),
    ('Report',                'RPT',          'Internal and external reports'),
    ('Correspondence',        'COR',          'Emails and letters'),
    ('Scanned Form',          'SCF',          'Scanned physical forms'),
    ('Compliance',            'COMPLIANCE',   'AML, KYC and regulatory compliance documents'),
    ('Boarding Pass',         'BOARDINGPASS', 'Airline boarding passes'),
    ('Resume',                'RESUME',       'Curriculum vitae and resumes')
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
-- ecm_admin: OCR Templates (seeded from existing JSON files)
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_admin.ocr_templates (category_code, name, description, fields, created_by) VALUES
    ('IDENTITY',
     'Identity Document Extraction',
     'Government-issued identity document extraction',
     '[{"fieldName":"full_name","pattern":"(?:name|full name)[:\\\\s]+([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":""},{"fieldName":"document_number","pattern":"(?:licence no|license no|dl no|passport no|id no|document no)[:\\\\s#]*([A-Z0-9]{5,15})","defaultValue":""},{"fieldName":"date_of_birth","pattern":"(?:date of birth|dob|born)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4})","defaultValue":""},{"fieldName":"expiry_date","pattern":"(?:expiry|expires|expiration|exp)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4})","defaultValue":""}]'::jsonb,
     'system'),
    ('MORTGAGE',
     'Mortgage Document Extraction',
     'Mortgage loan document extraction',
     '[{"fieldName":"loan_amount","pattern":"(?:loan amount|principal)[:\\\\s]*\\\\$?([\\\\d,.]+)","defaultValue":""},{"fieldName":"borrower_name","pattern":"(?:borrower|applicant)[:\\\\s]+([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":""},{"fieldName":"property_address","pattern":"(?:property address|subject property)[:\\\\s]+([\\\\w\\\\s,]+(?:Ave|St|Rd|Blvd|Dr|Ln|Way)[\\\\w\\\\s,]*)","defaultValue":""},{"fieldName":"interest_rate","pattern":"(?:interest rate|rate)[:\\\\s]*([\\\\d.]+)\\\\s*%","defaultValue":""},{"fieldName":"loan_term_years","pattern":"(?:loan term|term)[:\\\\s]*(\\\\d+)\\\\s*(?:year|yr)","defaultValue":""}]'::jsonb,
     'system'),
    ('RESUME',
     'Resume / CV Extraction',
     'Resume / CV field extraction',
     '[{"fieldName":"full_name","pattern":"^([A-Z][a-z]+(?:\\\\s[A-Z][a-z]+)+)","defaultValue":null},{"fieldName":"email","pattern":"([a-zA-Z0-9._%+\\\\-]+@[a-zA-Z0-9.\\\\-]+\\\\.[a-zA-Z]{2,})","defaultValue":null},{"fieldName":"phone","pattern":"((?:\\\\+?1[\\\\s.-]?)?(?:\\\\(?\\\\d{3}\\\\)?[\\\\s.-]?)\\\\d{3}[\\\\s.-]?\\\\d{4})","defaultValue":null},{"fieldName":"linkedin","pattern":"(linkedin\\\\.com/in/[a-zA-Z0-9\\\\-]+)","defaultValue":null}]'::jsonb,
     'system'),
    ('BOARDINGPASS',
     'Boarding Pass Extraction',
     'Airline boarding pass field extraction',
     '[{"fieldName":"passenger_name","pattern":"(?:name|passenger)[:\\\\s]+([A-Z][A-Z/\\\\s]+[A-Z])","defaultValue":""},{"fieldName":"flight_number","pattern":"(?:flight|flt)[\\\\s#:]*([A-Z]{2}\\\\s?\\\\d{2,5})","defaultValue":""},{"fieldName":"origin","pattern":"(?:from|departure|origin)[:\\\\s]*([A-Z]{3})","defaultValue":""},{"fieldName":"destination","pattern":"(?:to|arrival|destination)[:\\\\s]*([A-Z]{3})","defaultValue":""},{"fieldName":"departure_date","pattern":"(?:date|departs?)[:\\\\s]*(\\\\d{1,2}[/-]\\\\d{1,2}[/-]\\\\d{2,4}|\\\\d{2}[A-Z]{3}\\\\d{2,4})","defaultValue":""},{"fieldName":"departure_time","pattern":"(?:departs?|time|dep)[:\\\\s]*(\\\\d{1,2}:\\\\d{2}(?:\\\\s?[AP]M)?)","defaultValue":""},{"fieldName":"seat","pattern":"(?:seat)[:\\\\s]*([0-9]{1,3}[A-F])","defaultValue":""},{"fieldName":"gate","pattern":"(?:gate)[:\\\\s]*([A-Z]?\\\\d{1,3}[A-Z]?)","defaultValue":""},{"fieldName":"boarding_group","pattern":"(?:boarding|group)[:\\\\s]*(GROUP\\\\s*\\\\d+|[A-Z]\\\\d*)","defaultValue":""},{"fieldName":"ticket_number","pattern":"(?:ticket|eticket|e-ticket)[:\\\\s#]*(\\\\d{3}[-]?\\\\d{10}|\\\\d{13})","defaultValue":""},{"fieldName":"frequent_flyer","pattern":"(?:frequent flyer|aeroplan|mileage|ffn)[:\\\\s#]*([A-Z0-9]{6,12})","defaultValue":""},{"fieldName":"carrier","pattern":"(?:carrier|airline|operated by)[:\\\\s]*([A-Z]{2})","defaultValue":""}]'::jsonb,
     'system');

-- Link OCR templates to their categories (by code)
UPDATE ecm_admin.ocr_templates ot
SET category_id = dc.id
FROM ecm_admin.document_categories dc
WHERE dc.code = ot.category_code;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin: Tenant Config
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
-- ecm_admin: Integration Configs
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


-- ═══════════════════════════════════════════════════════════════════════════════
-- POST-APPLY CHECKLIST
-- ═══════════════════════════════════════════════════════════════════════════════
--
-- 1. DELETE all V*.sql files from every module's db/migration/ directory
--    EXCEPT V1__baseline.sql (content: "-- baseline")
--
-- 2. docker compose down -v && docker compose up -d
--
-- 3. Start backend services in order:
--    ecm-identity → ecm-admin → ecm-document → ecm-ocr → ecm-workflow → ecm-eforms → ecm-gateway
--
-- 4. Verify: SELECT count(*) FROM ecm_admin.ocr_templates;  -- Expected: 4
--    Verify: SELECT count(*) FROM ecm_core.permissions;      -- Expected: 24
