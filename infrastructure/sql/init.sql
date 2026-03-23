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
    case_id         UUID,                   -- soft ref → ecm_core.cases.id
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | PENDING | REJECTED | CANCELLED
    enrolled_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    enrolled_by     VARCHAR(255) NOT NULL,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (party_id, product_line_id, product_id)
);
CREATE INDEX idx_ppe_party        ON ecm_core.party_product_enrollments(party_id);
CREATE INDEX idx_ppe_product_line ON ecm_core.party_product_enrollments(product_line_id);
CREATE INDEX idx_ppe_active       ON ecm_core.party_product_enrollments(is_active);
CREATE INDEX idx_ppe_case         ON ecm_core.party_product_enrollments(case_id);
CREATE INDEX idx_ppe_status       ON ecm_core.party_product_enrollments(status);

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
-- Status lifecycle (lobby model):
--   NEW → IN_PROGRESS → REVIEW_PENDING → UNDER_REVIEW
--     → PENDING_APPROVAL → APPROVED → COMPLETED
--   (also: REJECTED, CANCELLED, ON_HOLD at any point)
--   returned_from_review flag set when reviewer sends case back to IN_PROGRESS
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.cases (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref        VARCHAR(200)  UNIQUE,            -- LOS/CRM reference e.g. "LOAN-2026-00142"
    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
    product_id          INTEGER,                          -- soft ref → ecm_admin.products.id
    case_type           VARCHAR(50),                      -- LOAN_ORIGINATION, ACCOUNT_OPENING, etc.
    status              VARCHAR(50)   NOT NULL DEFAULT 'NEW',
    returned_from_review BOOLEAN      NOT NULL DEFAULT FALSE,
    assigned_to         VARCHAR(255),                     -- assigned person (email or Okta subject)
    assigned_to_name    VARCHAR(255),
    assigned_to_group   VARCHAR(100),                     -- assigned group (role name, e.g. ECM_REVIEWER)
    claimed_by          VARCHAR(255),                     -- who claimed from group queue
    claimed_by_name     VARCHAR(255),
    claimed_at          TIMESTAMPTZ,
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
CREATE INDEX idx_case_group      ON ecm_core.cases(assigned_to_group);
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
    -- workflow tracking (populated when a document workflow starts)
    workflow_instance_id     VARCHAR(200),  -- Flowable process instance ID
    workflow_status          VARCHAR(30),   -- ACTIVE | COMPLETED | TERMINATED | SUSPENDED
    current_task_name        VARCHAR(200),  -- human-readable task name
    current_task_assignee    VARCHAR(255),  -- who currently has the task
    -- verification tracking
    is_verified              BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_by              VARCHAR(255),
    verified_at              TIMESTAMPTZ,
    -- override tracking
    override_status          VARCHAR(20),   -- PENDING | APPROVED | DENIED
    bypassed_by              VARCHAR(255),
    bypassed_reason          TEXT,
    bypassed_at              TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_cdoc_case     ON ecm_core.case_documents(case_id);
CREATE INDEX idx_cdoc_doc      ON ecm_core.case_documents(document_id);
CREATE INDEX idx_cdoc_status   ON ecm_core.case_documents(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- Case Timeline Events  (audit trail for case lifecycle)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_timeline_events (
    id              SERIAL       PRIMARY KEY,
    case_id         UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    event_type      VARCHAR(50)  NOT NULL,
    -- CASE_CREATED, CASE_STATUS_CHANGED, CHECKLIST_ITEM_UPLOADED,
    -- CHECKLIST_ITEM_APPROVED, CHECKLIST_ITEM_REJECTED, CHECKLIST_ITEM_WAIVED,
    -- WORKFLOW_STARTED, WORKFLOW_COMPLETED, OVERRIDE_REQUESTED,
    -- OVERRIDE_APPROVED, OVERRIDE_DENIED, ADMIN_BYPASS, CASE_NOTE_ADDED
    description     TEXT,
    detail          TEXT,              -- additional context (reason, old→new status, etc.)
    actor           VARCHAR(255),      -- email or 'system'
    timestamp       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_case_timeline_case ON ecm_core.case_timeline_events(case_id);
CREATE INDEX idx_case_timeline_ts   ON ecm_core.case_timeline_events(timestamp DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- Case Override Requests  (non-admin requests bypass → admin reviews)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_override_requests (
    id                  SERIAL       PRIMARY KEY,
    case_id             UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    checklist_item_id   INTEGER      NOT NULL REFERENCES ecm_core.case_documents(id) ON DELETE CASCADE,
    item_name           VARCHAR(255),          -- denormalized for display
    reason              TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | DENIED
    requested_by        VARCHAR(255) NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_by         VARCHAR(255),
    review_reason       TEXT,
    reviewed_at         TIMESTAMPTZ
);
CREATE INDEX idx_override_case   ON ecm_core.case_override_requests(case_id);
CREATE INDEX idx_override_status ON ecm_core.case_override_requests(status);

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
    role             VARCHAR(30)  NOT NULL,  -- LAWYER | APPRAISER | NOTARY | TITLE_COMPANY | OTHER
    phone            VARCHAR(50),
    invite_token     UUID         DEFAULT gen_random_uuid(),
    token_expires_at TIMESTAMPTZ  NOT NULL DEFAULT (NOW() + INTERVAL '30 days'),
    otp_code             VARCHAR(6),
    otp_expires_at       TIMESTAMPTZ,
    failed_otp_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_otp_ip          VARCHAR(45),
    session_token        TEXT,
    session_ip           VARCHAR(45),
    session_expires_at   TIMESTAMPTZ,
    last_accessed_at     TIMESTAMPTZ,
    invited_by           VARCHAR(255),
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (case_id, email)
);
CREATE INDEX idx_ext_part_case   ON ecm_core.external_participants(case_id);
CREATE INDEX idx_ext_part_email  ON ecm_core.external_participants(email);
CREATE INDEX idx_ext_part_invite_token  ON ecm_core.external_participants(invite_token);

-- ─────────────────────────────────────────────────────────────────────────────
-- Case Document Shares  (controls which docs are visible to external participants)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_document_shares (
    id                      SERIAL       PRIMARY KEY,
    case_id                 UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    case_document_id        INTEGER      NOT NULL REFERENCES ecm_core.case_documents(id) ON DELETE CASCADE,
    participant_id          INTEGER      NOT NULL REFERENCES ecm_core.external_participants(id) ON DELETE CASCADE,
    shared_by               VARCHAR(255) NOT NULL,
    shared_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (case_document_id, participant_id)
);
CREATE INDEX idx_cds_case        ON ecm_core.case_document_shares(case_id);
CREATE INDEX idx_cds_participant ON ecm_core.case_document_shares(participant_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- External Uploads  (documents uploaded by external participants)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.external_uploads (
    id                 SERIAL       PRIMARY KEY,
    case_id            UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    participant_id     INTEGER      NOT NULL REFERENCES ecm_core.external_participants(id) ON DELETE CASCADE,
    document_id        UUID,                    -- soft ref → ecm_core.documents.id (after processing)
    original_filename  VARCHAR(500) NOT NULL,
    file_size_bytes    BIGINT,
    storage_path       VARCHAR(1000),           -- MinIO path
    description        TEXT,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ext_upload_case ON ecm_core.external_uploads(case_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- External Access Audit Log
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.external_access_log (
    id              BIGSERIAL    PRIMARY KEY,
    participant_id  INTEGER      NOT NULL REFERENCES ecm_core.external_participants(id) ON DELETE CASCADE,
    case_id         UUID         NOT NULL,
    event_type      VARCHAR(30)  NOT NULL,
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    detail          TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ext_access_participant ON ecm_core.external_access_log(participant_id);
CREATE INDEX idx_ext_access_case ON ecm_core.external_access_log(case_id);

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
    key           VARCHAR(100) PRIMARY KEY,
    value         TEXT         NOT NULL,
    default_value TEXT,
    description   VARCHAR(500),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
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
    process_key         VARCHAR(100) NOT NULL UNIQUE,
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
    ('ECM_SUPER_ADMIN','System-level super administrator',                     TRUE),
    ('ECM_ADMIN',      'Full system administration access',                    TRUE),
    ('ECM_DESIGNER',   'Can create and publish eForms and workflow templates', TRUE),
    ('ECM_BACKOFFICE', 'Standard back-office document and workflow access',    TRUE),
    ('ECM_REVIEWER',   'Can review and approve workflow tasks',                TRUE),
    ('ECM_READONLY',   'Read-only access to assigned departments',             TRUE)
ON CONFLICT (name) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Super Admin User (dev/local only — seeded so first login gets SUPER_ADMIN)
-- The user record is auto-created by Okta on first login.
-- This seed assigns the SUPER_ADMIN role if the user + role exist.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.user_roles (user_id, role_id)
SELECT u.id, r.id
FROM ecm_core.users u
CROSS JOIN ecm_core.roles r
WHERE u.email = 'ecm.superadmin@dev.local'
  AND r.name = 'ECM_SUPER_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM ecm_core.user_roles ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

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
-- ecm_core: Email Templates (admin-editable, used by ecm-notification)
-- Variables use {{variableName}} syntax — replaced at send time.
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.email_templates (template_key, name, subject_template, body_template) VALUES
(
    'OTP_VERIFICATION',
    'OTP Verification Code',
    'ECM — Your Verification Code',
    '<div style="font-family:sans-serif;max-width:400px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Verification Code</h2>
<p style="font-size:32px;font-weight:bold;letter-spacing:8px;color:#111;padding:16px 0;text-align:center;background:#f3f4f6;border-radius:8px">{{otp}}</p>
<p style="color:#6b7280;font-size:14px">This code expires in 10 minutes.</p>
<p style="color:#9ca3af;font-size:12px">If you did not request this code, please ignore this email.</p>
</div>'
),
(
    'PARTICIPANT_INVITE',
    'External Participant Invitation',
    'ECM — You''ve been added to a case',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Hello {{name}},</h2>
<p>You have been added as a <strong>{{role}}</strong> on a case in the ECM platform.</p>
<p>Click the button below to access the case documents:</p>
<p style="text-align:center;padding:16px 0">
<a href="{{inviteLink}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Access Case Portal</a></p>
<p style="color:#6b7280;font-size:14px">You will be asked to verify your email with a one-time code.</p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'USER_INVITE',
    'User Platform Invitation',
    'ECM — You''ve been invited to the platform',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Welcome to ECM Platform</h2>
<p>Hello {{displayName}},</p>
<p>You have been invited to the ECM Platform as <strong>{{role}}</strong>.</p>
<p>Click the button below to sign in:</p>
<p style="text-align:center;padding:16px 0">
<a href="{{signInLink}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Sign In to ECM</a></p>
<p style="color:#6b7280;font-size:14px">Use your organisation credentials to sign in.</p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'TASK_ASSIGNED',
    'Workflow Task Assigned',
    'ECM — New review task: {{taskName}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">New Review Task</h2>
<p>A new task has been assigned to your group: <strong>{{candidateGroup}}</strong></p>
<p><strong>Task:</strong> {{taskName}}</p>
<p><strong>Document:</strong> {{documentName}}</p>
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/backoffice/queue" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">Open Review Queue</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
),
(
    'CASE_STATUS_CHANGED',
    'Case Status Update',
    'ECM — Case {{caseRef}} status changed to {{status}}',
    '<div style="font-family:sans-serif;max-width:500px;margin:0 auto;padding:20px">
<h2 style="color:#1e40af">Case Status Update</h2>
<p>Case <strong>{{caseRef}}</strong> for customer <strong>{{customerName}}</strong> has been updated.</p>
<p><strong>New Status:</strong> {{status}}</p>
{{#reason}}<p><strong>Reason:</strong> {{reason}}</p>{{/reason}}
<p style="text-align:center;padding:16px 0">
<a href="{{appUrl}}/cases/{{caseId}}" style="background:#2563eb;color:white;padding:12px 24px;border-radius:8px;text-decoration:none;font-weight:bold">View Case</a></p>
<p style="color:#9ca3af;font-size:12px">— ECM Platform</p>
</div>'
)
ON CONFLICT (template_key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: RBAC Modules
-- ─────────────────────────────────────────────────────────────────────────────
INSERT INTO ecm_core.modules (code, name, sort_order) VALUES
    ('DOCUMENTS', 'Document Management', 1),
    ('WORKFLOW',  'Workflow & Tasks',     2),
    ('EFORMS',    'Electronic Forms',     3),
    ('CASE',      'Case Management',      4),
    ('ADMIN',     'Administration',       5),
    ('OCR',       'OCR & Scanning',       6),
    ('ARCHIVE',   'Archive & Retention',  7);

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
    ('ARCHIVE',   'manage',  'archive:manage', 'Manage retention policies'),
    ('CASE',      'VIEW',    'CASE:VIEW',   'View cases and case details'),
    ('CASE',      'CREATE',  'CASE:CREATE', 'Create new cases'),
    ('CASE',      'UPDATE',  'CASE:UPDATE', 'Update case status, verify items, add notes'),
    ('CASE',      'DELETE',  'CASE:DELETE', 'Delete and cancel cases'),
    ('CASE',      'ASSIGN',  'CASE:ASSIGN', 'Assign and reassign cases'),
    ('CASE',      'VERIFY',  'CASE:VERIFY', 'Verify checklist items'),
    ('ADMIN',     'PRODUCT_VIEW',   'PRODUCT:VIEW',   'View products and catalogue'),
    ('ADMIN',     'PRODUCT_CREATE', 'PRODUCT:CREATE', 'Create products'),
    ('ADMIN',     'PRODUCT_UPDATE', 'PRODUCT:UPDATE', 'Update products and document types'),
    ('ADMIN',     'PRODUCT_DELETE', 'PRODUCT:DELETE', 'Deactivate products'),
    ('ADMIN',     'CUSTOMER_VIEW',   'CUSTOMER:VIEW',   'View customers and enrollments'),
    ('ADMIN',     'CUSTOMER_CREATE', 'CUSTOMER:CREATE', 'Create customers'),
    ('ADMIN',     'CUSTOMER_UPDATE', 'CUSTOMER:UPDATE', 'Update customers and enrollments'),
    ('ADMIN',     'CUSTOMER_DELETE', 'CUSTOMER:DELETE', 'Deactivate customers');

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core: Role → Permission grants
-- ─────────────────────────────────────────────────────────────────────────────

-- ECM_ADMIN: all permissions (cross join — automatically includes new permissions)
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
    'ocr:view',       'archive:read',
    'CASE:VIEW', 'CASE:CREATE', 'CASE:UPDATE', 'CASE:VERIFY', 'CASE:ASSIGN',
    'PRODUCT:VIEW',
    'CUSTOMER:VIEW', 'CUSTOMER:CREATE', 'CUSTOMER:UPDATE'
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
    'archive:read',
    'CASE:VIEW', 'CASE:UPDATE', 'CASE:VERIFY', 'CASE:ASSIGN',
    'PRODUCT:VIEW',
    'CUSTOMER:VIEW'
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
INSERT INTO ecm_admin.tenant_config (key, value, default_value, description) VALUES
    ('tenant.name',              'ECM Platform', 'ECM Platform', 'Organisation display name'),
    ('tenant.logo_url',          '',             '',             'Logo URL for header branding'),
    ('tenant.support_email',     '',             '',             'Support email shown in UI footer'),
    ('tenant.timezone',          'UTC',          'UTC',          'Default timezone for date display'),
    -- Navigation & Header
    ('theme.sidebar_bg',        '#002347',      '#002347',      'Sidebar background colour'),
    ('theme.sidebar_active',    '#00A651',      '#00A651',      'Sidebar active item / badge colour'),
    ('theme.header_bg',         '#ffffff',      '#ffffff',      'Header bar background colour'),
    ('theme.header_text',       '#111827',      '#111827',      'Header title text colour'),
    -- Main Content
    ('theme.accent',            '#4f46e5',      '#4f46e5',      'Buttons, links, focus rings, active states'),
    ('theme.page_bg',           '#f4f6f9',      '#f4f6f9',      'Main content area background'),
    -- Webhooks
    ('webhook.document_indexed.url',    '',  '',             'POST callback URL when document reaches INDEXED status'),
    ('webhook.submission_signed.url',   '',  '',             'POST callback URL when DocuSign signing is confirmed')
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
     'document-compliance-review', 'ECM_REVIEWER', TRUE, 24),
    ('Form Admin Triage Review',
     'Form submission routed by admin to backoffice or reviewer. Reviewer can request additional documents.',
     'form-admin-triage-review', 'ECM_ADMIN', TRUE, 48)
ON CONFLICT (process_key) DO NOTHING;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow: Seed Workflow Templates (BPMN XML for Flowable deployment)
--
-- Two production-ready workflows:
--   1. document-single-review  — Single-step backoffice review
--   2. document-dual-review    — Two-step: backoffice triage → underwriter approval
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO ecm_workflow.workflow_templates
    (name, description, process_key, dsl_definition, bpmn_xml, bpmn_source, status, version, is_default, sla_hours, warning_threshold_pct)
VALUES
-- 1. Single-step backoffice review
(
    'General Document Review',
    'Default single-step review by backoffice team. Document is reviewed and either approved or rejected.',
    'document-single-review',
    '{"processKey":"document-single-review","name":"General Document Review","steps":[{"id":"review","type":"USER_TASK","name":"Backoffice Review","candidateGroups":"ECM_BACKOFFICE","outcomes":["APPROVED","REJECTED"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">

  <process id="document-single-review" name="General Document Review" isExecutable="true">

    <startEvent id="start" name="Start" />

    <sequenceFlow id="flow_start" sourceRef="start" targetRef="review" />

    <userTask id="review" name="Backoffice Review"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete"
            delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_review_gw" sourceRef="review" targetRef="gw_review" />

    <exclusiveGateway id="gw_review" name="Review Decision" />

    <sequenceFlow id="flow_approved" sourceRef="gw_review" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>

    <sequenceFlow id="flow_rejected" sourceRef="gw_review" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>

    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end"
            delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

    <endEvent id="end_rejected" name="Rejected">
      <extensionElements>
        <flowable:executionListener event="end"
            delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

  </process>

  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="document-single-review">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="180" y="200" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="review_di" bpmnElement="review">
        <omgdc:Bounds x="300" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_review_di" bpmnElement="gw_review" isMarkerVisible="true">
        <omgdc:Bounds x="530" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="660" y="130" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="660" y="270" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="216" y="218" />
        <omgdi:waypoint x="300" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_review_gw_di" bpmnElement="flow_review_gw">
        <omgdi:waypoint x="460" y="218" />
        <omgdi:waypoint x="530" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_approved_di" bpmnElement="flow_approved">
        <omgdi:waypoint x="555" y="193" />
        <omgdi:waypoint x="555" y="148" />
        <omgdi:waypoint x="660" y="148" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_rejected_di" bpmnElement="flow_rejected">
        <omgdi:waypoint x="555" y="243" />
        <omgdi:waypoint x="555" y="288" />
        <omgdi:waypoint x="660" y="288" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, TRUE, 48, 80
),

-- 2. Two-step: backoffice triage → underwriter approval
(
    'Underwriter Review',
    'Two-step workflow: backoffice triage then underwriter approval. Used for loan documents requiring dual sign-off.',
    'document-dual-review',
    '{"processKey":"document-dual-review","name":"Underwriter Review","steps":[{"id":"triage","type":"USER_TASK","name":"Backoffice Triage","candidateGroups":"ECM_BACKOFFICE","outcomes":["FORWARD","REJECTED"]},{"id":"underwriter_review","type":"USER_TASK","name":"Underwriter Review","candidateGroups":"ECM_REVIEWER","outcomes":["APPROVED","REJECTED","RETURN"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">

  <process id="document-dual-review" name="Underwriter Review" isExecutable="true">

    <startEvent id="start" name="Start" />

    <sequenceFlow id="flow_start" sourceRef="start" targetRef="triage" />

    <!-- Step 1: Backoffice Triage -->
    <userTask id="triage" name="Backoffice Triage"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete"
            delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_triage_gw" sourceRef="triage" targetRef="gw_triage" />

    <exclusiveGateway id="gw_triage" name="Triage Decision" />

    <sequenceFlow id="flow_triage_forward" sourceRef="gw_triage" targetRef="underwriter_review">
      <conditionExpression>${decision == ''FORWARD'' || decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>

    <sequenceFlow id="flow_triage_reject" sourceRef="gw_triage" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>

    <!-- Step 2: Underwriter Review -->
    <userTask id="underwriter_review" name="Underwriter Review"
              flowable:candidateGroups="ECM_REVIEWER"
              flowable:formKey="review-form">
      <extensionElements>
        <flowable:taskListener event="complete"
            delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>

    <sequenceFlow id="flow_uw_gw" sourceRef="underwriter_review" targetRef="gw_underwriter" />

    <exclusiveGateway id="gw_underwriter" name="Underwriter Decision" />

    <sequenceFlow id="flow_uw_approved" sourceRef="gw_underwriter" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>

    <sequenceFlow id="flow_uw_rejected" sourceRef="gw_underwriter" targetRef="end_rejected_uw">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>

    <sequenceFlow id="flow_uw_return" sourceRef="gw_underwriter" targetRef="triage">
      <conditionExpression>${decision == ''RETURN''}</conditionExpression>
    </sequenceFlow>

    <!-- End States -->
    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end"
            delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

    <endEvent id="end_rejected" name="Rejected (Triage)">
      <extensionElements>
        <flowable:executionListener event="end"
            delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

    <endEvent id="end_rejected_uw" name="Rejected (Underwriter)">
      <extensionElements>
        <flowable:executionListener event="end"
            delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>

  </process>

  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="document-dual-review">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="100" y="200" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="triage_di" bpmnElement="triage">
        <omgdc:Bounds x="210" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_triage_di" bpmnElement="gw_triage" isMarkerVisible="true">
        <omgdc:Bounds x="430" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="underwriter_review_di" bpmnElement="underwriter_review">
        <omgdc:Bounds x="550" y="178" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_underwriter_di" bpmnElement="gw_underwriter" isMarkerVisible="true">
        <omgdc:Bounds x="770" y="193" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="900" y="130" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="440" y="320" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_uw_di" bpmnElement="end_rejected_uw">
        <omgdc:Bounds x="900" y="270" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="136" y="218" />
        <omgdi:waypoint x="210" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_gw_di" bpmnElement="flow_triage_gw">
        <omgdi:waypoint x="370" y="218" />
        <omgdi:waypoint x="430" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_forward_di" bpmnElement="flow_triage_forward">
        <omgdi:waypoint x="480" y="218" />
        <omgdi:waypoint x="550" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_reject_di" bpmnElement="flow_triage_reject">
        <omgdi:waypoint x="455" y="243" />
        <omgdi:waypoint x="455" y="320" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_gw_di" bpmnElement="flow_uw_gw">
        <omgdi:waypoint x="710" y="218" />
        <omgdi:waypoint x="770" y="218" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_approved_di" bpmnElement="flow_uw_approved">
        <omgdi:waypoint x="795" y="193" />
        <omgdi:waypoint x="795" y="148" />
        <omgdi:waypoint x="900" y="148" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_rejected_di" bpmnElement="flow_uw_rejected">
        <omgdi:waypoint x="795" y="243" />
        <omgdi:waypoint x="795" y="288" />
        <omgdi:waypoint x="900" y="288" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_uw_return_di" bpmnElement="flow_uw_return">
        <omgdi:waypoint x="770" y="218" />
        <omgdi:waypoint x="740" y="120" />
        <omgdi:waypoint x="290" y="120" />
        <omgdi:waypoint x="290" y="178" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, FALSE, 24, 80
)
ON CONFLICT (process_key) DO NOTHING;

-- 3. Form Admin Triage Review (form → admin → backoffice/reviewer → approve/reject/additional docs)
INSERT INTO ecm_workflow.workflow_templates
    (name, description, process_key, dsl_definition, bpmn_xml, bpmn_source, status, version, is_default, sla_hours, warning_threshold_pct)
VALUES (
    'Form Admin Triage Review',
    'Form submission triggers admin triage. Admin routes to backoffice or directly to reviewer. Reviewer can approve, reject, or send back to backoffice for additional documents.',
    'form-admin-triage-review',
    '{"processKey":"form-admin-triage-review","name":"Form Admin Triage Review","steps":[{"id":"admin_triage","type":"USER_TASK","name":"Admin Triage","candidateGroups":"ECM_ADMIN","outcomes":["TO_BACKOFFICE","TO_REVIEWER"]},{"id":"backoffice_review","type":"USER_TASK","name":"Backoffice Review","candidateGroups":"ECM_BACKOFFICE","outcomes":["FORWARD"]},{"id":"reviewer_approval","type":"USER_TASK","name":"Reviewer Approval","candidateGroups":"ECM_REVIEWER","outcomes":["APPROVED","REJECTED","ADDITIONAL_DOCS"]}],"endStates":[{"id":"end_approved","name":"Approved","status":"APPROVED"},{"id":"end_rejected","name":"Rejected","status":"REJECTED"}]}'::jsonb,
    '<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
             targetNamespace="http://www.flowable.org/processdef">
  <process id="form-admin-triage-review" name="Form Admin Triage Review" isExecutable="true">
    <startEvent id="start" name="Form Submitted" />
    <sequenceFlow id="flow_start" sourceRef="start" targetRef="admin_triage" />
    <userTask id="admin_triage" name="Admin Triage"
              flowable:candidateGroups="ECM_ADMIN"
              flowable:formKey="review-form">
      <documentation>Admin reviews the submitted form and decides routing:
        TO_BACKOFFICE = needs backoffice document collection first
        TO_REVIEWER = ready for direct reviewer approval</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_triage_gw" sourceRef="admin_triage" targetRef="gw_triage" />
    <exclusiveGateway id="gw_triage" name="Routing Decision" />
    <sequenceFlow id="flow_to_backoffice" sourceRef="gw_triage" targetRef="backoffice_review">
      <conditionExpression>${decision == ''TO_BACKOFFICE'' || decision == ''REJECTED'' || decision == ''PASS''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_to_reviewer" sourceRef="gw_triage" targetRef="reviewer_approval">
      <conditionExpression>${decision == ''TO_REVIEWER'' || decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>
    <userTask id="backoffice_review" name="Backoffice Review"
              flowable:candidateGroups="ECM_BACKOFFICE"
              flowable:formKey="review-form">
      <documentation>Backoffice collects/verifies documents, then forwards to reviewer.</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_bo_to_reviewer" sourceRef="backoffice_review" targetRef="reviewer_approval" />
    <userTask id="reviewer_approval" name="Reviewer Approval"
              flowable:candidateGroups="ECM_REVIEWER"
              flowable:formKey="review-form">
      <documentation>Final review: APPROVED, REJECTED, or ADDITIONAL_DOCS (back to backoffice)</documentation>
      <extensionElements>
        <flowable:taskListener event="complete" delegateExpression="${taskCompletedListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_reviewer_gw" sourceRef="reviewer_approval" targetRef="gw_reviewer" />
    <exclusiveGateway id="gw_reviewer" name="Reviewer Decision" />
    <sequenceFlow id="flow_rv_approved" sourceRef="gw_reviewer" targetRef="end_approved">
      <conditionExpression>${decision == ''APPROVED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_rv_rejected" sourceRef="gw_reviewer" targetRef="end_rejected">
      <conditionExpression>${decision == ''REJECTED''}</conditionExpression>
    </sequenceFlow>
    <sequenceFlow id="flow_rv_additional" sourceRef="gw_reviewer" targetRef="backoffice_review">
      <conditionExpression>${decision == ''ADDITIONAL_DOCS''}</conditionExpression>
    </sequenceFlow>
    <endEvent id="end_approved" name="Approved">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
    <endEvent id="end_rejected" name="Rejected">
      <extensionElements>
        <flowable:executionListener event="end" delegateExpression="${processEndListener}" />
      </extensionElements>
    </endEvent>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane bpmnElement="form-admin-triage-review">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start">
        <omgdc:Bounds x="80" y="250" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="admin_triage_di" bpmnElement="admin_triage">
        <omgdc:Bounds x="190" y="228" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_triage_di" bpmnElement="gw_triage" isMarkerVisible="true">
        <omgdc:Bounds x="420" y="243" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="backoffice_review_di" bpmnElement="backoffice_review">
        <omgdc:Bounds x="530" y="340" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="reviewer_approval_di" bpmnElement="reviewer_approval">
        <omgdc:Bounds x="730" y="228" width="160" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="gw_reviewer_di" bpmnElement="gw_reviewer" isMarkerVisible="true">
        <omgdc:Bounds x="960" y="243" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_approved_di" bpmnElement="end_approved">
        <omgdc:Bounds x="1090" y="180" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_rejected_di" bpmnElement="end_rejected">
        <omgdc:Bounds x="1090" y="320" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_di" bpmnElement="flow_start">
        <omgdi:waypoint x="116" y="268" />
        <omgdi:waypoint x="190" y="268" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_triage_gw_di" bpmnElement="flow_triage_gw">
        <omgdi:waypoint x="350" y="268" />
        <omgdi:waypoint x="420" y="268" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_to_backoffice_di" bpmnElement="flow_to_backoffice">
        <omgdi:waypoint x="445" y="293" />
        <omgdi:waypoint x="445" y="380" />
        <omgdi:waypoint x="530" y="380" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_to_reviewer_di" bpmnElement="flow_to_reviewer">
        <omgdi:waypoint x="470" y="268" />
        <omgdi:waypoint x="730" y="268" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_bo_to_reviewer_di" bpmnElement="flow_bo_to_reviewer">
        <omgdi:waypoint x="690" y="380" />
        <omgdi:waypoint x="810" y="380" />
        <omgdi:waypoint x="810" y="308" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_reviewer_gw_di" bpmnElement="flow_reviewer_gw">
        <omgdi:waypoint x="890" y="268" />
        <omgdi:waypoint x="960" y="268" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_rv_approved_di" bpmnElement="flow_rv_approved">
        <omgdi:waypoint x="985" y="243" />
        <omgdi:waypoint x="985" y="198" />
        <omgdi:waypoint x="1090" y="198" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_rv_rejected_di" bpmnElement="flow_rv_rejected">
        <omgdi:waypoint x="985" y="293" />
        <omgdi:waypoint x="985" y="338" />
        <omgdi:waypoint x="1090" y="338" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_rv_additional_di" bpmnElement="flow_rv_additional">
        <omgdi:waypoint x="960" y="268" />
        <omgdi:waypoint x="940" y="450" />
        <omgdi:waypoint x="610" y="450" />
        <omgdi:waypoint x="610" y="420" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>',
    'VISUAL', 'PUBLISHED', 1, FALSE, 48, 80
)
ON CONFLICT (process_key) DO NOTHING;


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
