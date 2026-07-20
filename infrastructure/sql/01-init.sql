-- ═══════════════════════════════════════════════════════════════════════════════
-- ECM Platform — Master Database Initialisation  v5.0
--
-- REPLACES: infrastructure/sql/init.sql  (v4.0)
-- CONSOLIDATES all previous migrations from all modules:
--   ecm-document V2 (archive columns), V3 (locking columns), V4 (annotations)
--   ecm-workflow V2 (template tags), V3 (category_workflow_mappings)
--
-- NEW in v5.0 (Batch Processing & Storage Redesign):
--   ecm_batch schema                     — batch processing module
--   ecm_batch.batch_jobs                 — batch job lifecycle
--   ecm_batch.batch_items                — per-document classification + review
--   ecm_core.documents redesign:
--     - storage_key (UUID-based flat MinIO path)
--     - storage_bucket
--     - checksum (SHA-256)
--     - classification_source, classification_confidence
--     - lock_type (USER, CASE, BATCH)
--     - Removed blob_storage_path (replaced by storage_key + storage_bucket)
--     - Consolidated locking columns from V3 migration
--     - Consolidated archive/delete columns from V2 migration
--   ecm_core.document_labels             — key-value tags on documents
--   ecm_admin.label_definitions          — admin-configurable label keys
--   ecm_admin.watch_folder_config        — scanner folder polling config
--
-- HOW TO APPLY (clean start):
--   docker compose down -v
--   docker compose up -d   (postgres runs this file via /docker-entrypoint-initdb.d/)
--
-- AFTER APPLYING:
--   1. Delete ALL V*.sql files from every module's db/migration/ directory.
--   2. Add a single V1__baseline.sql (content: "SELECT 1;") to each module.
--   3. Future schema changes go into V2__*.sql in the relevant module.
--
-- Schema layout:
--   ecm_core     — shared domain: users, parties, cases, documents, labels, notifications
--   ecm_audit    — immutable audit trail
--   ecm_admin    — product catalogue, segments, tenant config, integrations, OCR templates, label definitions
--   ecm_workflow — Flowable BPM bridge, templates, SLA, task history
--   ecm_forms    — eForms engine: definitions, submissions, DocuSign events
--   ecm_batch    — batch processing: jobs, items, classification, review queue
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- SCHEMAS
-- ─────────────────────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS ecm_core;
CREATE SCHEMA IF NOT EXISTS ecm_audit;
CREATE SCHEMA IF NOT EXISTS ecm_admin;
CREATE SCHEMA IF NOT EXISTS ecm_workflow;
CREATE SCHEMA IF NOT EXISTS ecm_forms;
CREATE SCHEMA IF NOT EXISTS ecm_batch;


-- ═══════════════════════════════════════════════════════════════════════════════
--  ECM_CORE
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.departments
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
-- ecm_core.roles
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
-- ecm_core.users
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.users (
    id               SERIAL       PRIMARY KEY,
    entra_object_id  VARCHAR(255) UNIQUE,
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
-- ecm_core.user_roles
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
-- ecm_core.modules
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.modules (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.permissions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.permissions (
    id          SERIAL       PRIMARY KEY,
    module_code VARCHAR(50)  NOT NULL REFERENCES ecm_core.modules(code),
    action      VARCHAR(50)  NOT NULL,
    code        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (module_code, action)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.role_permissions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.role_permissions (
    role_id       INTEGER      NOT NULL REFERENCES ecm_core.roles(id),
    permission_id INTEGER      NOT NULL REFERENCES ecm_core.permissions(id),
    granted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by    VARCHAR(255),
    PRIMARY KEY (role_id, permission_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.capability_bundles
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.capability_bundles (
    id          SERIAL       PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INTEGER      NOT NULL DEFAULT 0
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.bundle_permissions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.bundle_permissions (
    bundle_id     INTEGER NOT NULL REFERENCES ecm_core.capability_bundles(id),
    permission_id INTEGER NOT NULL REFERENCES ecm_core.permissions(id),
    PRIMARY KEY (bundle_id, permission_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.parties
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.parties (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id      VARCHAR(100) NOT NULL UNIQUE,
    party_type       VARCHAR(20)  NOT NULL,
    segment_id       INTEGER      NOT NULL,
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
-- ecm_core.party_product_enrollments
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.party_product_enrollments (
    id              SERIAL      PRIMARY KEY,
    party_id        UUID        NOT NULL REFERENCES ecm_core.parties(id) ON DELETE CASCADE,
    product_line_id INTEGER     NOT NULL,
    product_id      INTEGER,
    case_id         UUID,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
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
-- ecm_core.documents  (REDESIGNED for UUID storage in v5.0)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.documents (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(500)  NOT NULL,
    original_filename   VARCHAR(500)  NOT NULL,
    mime_type           VARCHAR(100)  NOT NULL,
    file_size_bytes     BIGINT,
    checksum            VARCHAR(128),
    -- Storage (UUID-based flat MinIO)
    storage_key         VARCHAR(500)  NOT NULL,
    storage_bucket      VARCHAR(100)  NOT NULL DEFAULT 'ecm-documents',
    -- Business context (all optional — filled progressively or by classification)
    category_id         INTEGER,
    department_id       INTEGER       REFERENCES ecm_core.departments(id) ON DELETE SET NULL,
    segment_id          INTEGER,
    product_line_id     INTEGER,
    -- Party link
    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
    party_external_id   VARCHAR(100),
    -- Uploader
    uploaded_by         INTEGER       REFERENCES ecm_core.users(id) ON DELETE SET NULL,
    uploaded_by_email   VARCHAR(255),
    -- Status & lifecycle
    status              VARCHAR(50)   NOT NULL DEFAULT 'PENDING_OCR',
    -- Versioning
    version             INTEGER       NOT NULL DEFAULT 1,
    parent_doc_id       UUID          REFERENCES ecm_core.documents(id),
    is_latest_version   BOOLEAN       NOT NULL DEFAULT TRUE,
    -- OCR
    ocr_completed       BOOLEAN       NOT NULL DEFAULT FALSE,
    extracted_text      TEXT,
    extracted_fields    JSONB,
    -- Classification (batch processing)
    classification_source     VARCHAR(30),
    classification_confidence DECIMAL(5,2),
    -- Locking (consolidated: user lock + case lock + batch lock)
    locked_by           VARCHAR(255),
    locked_at           TIMESTAMPTZ,
    lock_expires_at     TIMESTAMPTZ,
    lock_type           VARCHAR(20),
    opt_lock_version    BIGINT        DEFAULT 0,
    -- Archive/delete audit
    archived_at         TIMESTAMPTZ,
    archived_by         VARCHAR(255),
    deleted_at          TIMESTAMPTZ,
    deleted_by          VARCHAR(255),
    delete_reason       VARCHAR(500),
    purged_at           TIMESTAMPTZ,
    -- Flexible metadata
    metadata            JSONB,
    tags                TEXT[],
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- Constraints
    CONSTRAINT ck_classification_source CHECK (classification_source IS NULL OR classification_source IN ('MANUAL', 'AUTO_CLASSIFIED', 'AUTO_CLASSIFIED_VERIFIED', 'MANUAL_VERIFIED', 'QR_CODE', 'MIGRATION', 'BATCH', 'VERIFIED', 'EFORM')),
    CONSTRAINT ck_lock_type CHECK (lock_type IS NULL OR lock_type IN ('USER', 'CASE', 'BATCH'))
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
CREATE INDEX idx_core_docs_storage      ON ecm_core.documents(storage_key);
CREATE INDEX idx_core_docs_classification ON ecm_core.documents(classification_source);
CREATE INDEX idx_core_docs_status_created ON ecm_core.documents(status, created_at) WHERE status IN ('ACTIVE', 'ARCHIVED');
CREATE INDEX idx_core_docs_locked       ON ecm_core.documents(lock_expires_at) WHERE locked_by IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.document_labels  (NEW in v5.0)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.document_labels (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID         NOT NULL REFERENCES ecm_core.documents(id) ON DELETE CASCADE,
    label_key       VARCHAR(100) NOT NULL,
    label_value     VARCHAR(500) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_doc_labels_doc       ON ecm_core.document_labels(document_id);
CREATE INDEX idx_doc_labels_key_value ON ecm_core.document_labels(label_key, label_value);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.document_annotations  (consolidated from V4 migration)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.document_annotations (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID            NOT NULL,
    case_id         UUID,
    page_number     INTEGER         NOT NULL DEFAULT 1,
    x_percent       DOUBLE PRECISION NOT NULL,
    y_percent       DOUBLE PRECISION NOT NULL,
    comment         TEXT            NOT NULL,
    author_email    VARCHAR(255)    NOT NULL,
    author_name     VARCHAR(255),
    parent_id       UUID            REFERENCES ecm_core.document_annotations(id),
    resolved        BOOLEAN         NOT NULL DEFAULT false,
    resolved_by     VARCHAR(255),
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_doc_annotations_doc    ON ecm_core.document_annotations(document_id);
CREATE INDEX idx_doc_annotations_case   ON ecm_core.document_annotations(case_id);
CREATE INDEX idx_doc_annotations_parent ON ecm_core.document_annotations(parent_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.cases
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.cases (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    external_ref        VARCHAR(200)  UNIQUE,
    party_id            UUID          REFERENCES ecm_core.parties(id) ON DELETE SET NULL,
    product_id          INTEGER,
    case_type           VARCHAR(50),
    status              VARCHAR(50)   NOT NULL DEFAULT 'NEW',
    returned_from_review BOOLEAN      NOT NULL DEFAULT FALSE,
    assigned_to         VARCHAR(255),
    assigned_to_name    VARCHAR(255),
    assigned_to_group   VARCHAR(100),
    claimed_by          VARCHAR(255),
    claimed_by_name     VARCHAR(255),
    claimed_at          TIMESTAMPTZ,
    source_system       VARCHAR(50)   NOT NULL DEFAULT 'ECM',
    source_ref          VARCHAR(200),
    process_instance_id VARCHAR(200),
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
CREATE INDEX idx_case_group       ON ecm_core.cases(assigned_to_group);
CREATE INDEX idx_case_source      ON ecm_core.cases(source_system);
CREATE INDEX idx_case_external    ON ecm_core.cases(external_ref);
CREATE INDEX idx_case_created     ON ecm_core.cases(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.case_documents
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_documents (
    id                       SERIAL       PRIMARY KEY,
    case_id                  UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    product_document_type_id INTEGER      NOT NULL,
    document_id              UUID,
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    uploaded_by              VARCHAR(255),
    uploaded_at              TIMESTAMPTZ,
    reviewed_by              VARCHAR(255),
    reviewed_at              TIMESTAMPTZ,
    review_notes             TEXT,
    waived_by                VARCHAR(255),
    waived_reason            TEXT,
    workflow_instance_id     VARCHAR(200),
    workflow_status          VARCHAR(30),
    current_task_name        VARCHAR(200),
    current_task_assignee    VARCHAR(255),
    is_verified              BOOLEAN      NOT NULL DEFAULT FALSE,
    verified_by              VARCHAR(255),
    verified_at              TIMESTAMPTZ,
    override_status          VARCHAR(20),
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
-- ecm_core.case_timeline_events
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_timeline_events (
    id              SERIAL       PRIMARY KEY,
    case_id         UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    event_type      VARCHAR(50)  NOT NULL,
    description     TEXT,
    detail          TEXT,
    actor           VARCHAR(255),
    timestamp       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_case_timeline_case ON ecm_core.case_timeline_events(case_id);
CREATE INDEX idx_case_timeline_ts   ON ecm_core.case_timeline_events(timestamp DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.case_override_requests
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.case_override_requests (
    id                  SERIAL       PRIMARY KEY,
    case_id             UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    checklist_item_id   INTEGER      NOT NULL REFERENCES ecm_core.case_documents(id) ON DELETE CASCADE,
    item_name           VARCHAR(255),
    reason              TEXT         NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_by        VARCHAR(255) NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_by         VARCHAR(255),
    review_reason       TEXT,
    reviewed_at         TIMESTAMPTZ
);
CREATE INDEX idx_override_case   ON ecm_core.case_override_requests(case_id);
CREATE INDEX idx_override_status ON ecm_core.case_override_requests(status);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.external_participants
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.external_participants (
    id               SERIAL       PRIMARY KEY,
    case_id          UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    organization     VARCHAR(200),
    role             VARCHAR(30)  NOT NULL,
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
-- ecm_core.case_document_shares
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
-- ecm_core.external_uploads
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.external_uploads (
    id                 SERIAL       PRIMARY KEY,
    case_id            UUID         NOT NULL REFERENCES ecm_core.cases(id) ON DELETE CASCADE,
    participant_id     INTEGER      NOT NULL REFERENCES ecm_core.external_participants(id) ON DELETE CASCADE,
    document_id        UUID,
    original_filename  VARCHAR(500) NOT NULL,
    file_size_bytes    BIGINT,
    storage_path       VARCHAR(1000),
    description        TEXT,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ext_upload_case ON ecm_core.external_uploads(case_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.external_access_log
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
-- ecm_core.notifications
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_core.notifications (
    id              BIGSERIAL    PRIMARY KEY,
    recipient       VARCHAR(255) NOT NULL,
    title           VARCHAR(300) NOT NULL,
    body            TEXT,
    link            VARCHAR(500),
    category        VARCHAR(50)  DEFAULT 'GENERAL',
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notif_recipient ON ecm_core.notifications(recipient, is_read);
CREATE INDEX idx_notif_created   ON ecm_core.notifications(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_core.email_templates
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
-- ecm_core.email_queue
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
-- ecm_core.notification_preferences
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
--  ECM_AUDIT
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_audit.audit_log
-- ─────────────────────────────────────────────────────────────────────────────
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
--  ECM_ADMIN
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.segments
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
-- ecm_admin.product_lines
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
-- ecm_admin.document_categories
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
-- ecm_admin.products
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.products (
    id                SERIAL       PRIMARY KEY,
    product_code      VARCHAR(50)  NOT NULL UNIQUE,
    display_name      VARCHAR(200) NOT NULL,
    description       TEXT,
    product_schema    JSONB,
    segment_id        INTEGER      REFERENCES ecm_admin.segments(id),
    product_line_id   INTEGER      REFERENCES ecm_admin.product_lines(id),
    case_workflow_key VARCHAR(100),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_product_code        ON ecm_admin.products(product_code);
CREATE INDEX idx_admin_product_active      ON ecm_admin.products(is_active);
CREATE INDEX idx_admin_product_segment     ON ecm_admin.products(segment_id);
CREATE INDEX idx_admin_product_line        ON ecm_admin.products(product_line_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.product_document_types
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.product_document_types (
    id                 SERIAL       PRIMARY KEY,
    product_id         INTEGER      NOT NULL REFERENCES ecm_admin.products(id) ON DELETE CASCADE,
    category_id        INTEGER      NOT NULL REFERENCES ecm_admin.document_categories(id) ON DELETE CASCADE,
    name               VARCHAR(200) NOT NULL,
    code               VARCHAR(100) NOT NULL,
    source_type        VARCHAR(20)  NOT NULL DEFAULT 'UPLOAD',
    form_definition_id UUID,
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
-- ecm_admin.ocr_templates
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
-- ecm_admin.glm_ocr_examples — few-shot training data for GLM-OCR engine
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.glm_ocr_examples (
    id              BIGSERIAL    PRIMARY KEY,
    category_code   VARCHAR(50)  NOT NULL,
    source          VARCHAR(20)  NOT NULL DEFAULT 'AZURE',
    document_hash   VARCHAR(64),
    expected_output JSONB        NOT NULL,
    confidence      NUMERIC(5,2),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(100),
    CONSTRAINT ck_glm_source CHECK (source IN ('AZURE', 'MANUAL', 'REGION'))
);
CREATE INDEX idx_glm_examples_category ON ecm_admin.glm_ocr_examples(category_code);
CREATE UNIQUE INDEX idx_glm_examples_hash ON ecm_admin.glm_ocr_examples(document_hash) WHERE document_hash IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.external_product_refs
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
-- ecm_admin.retention_policies
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
-- ecm_admin.tenant_config
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.tenant_config (
    key           VARCHAR(100) PRIMARY KEY,
    value         TEXT         NOT NULL,
    default_value TEXT,
    description   VARCHAR(500),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.integration_configs
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
-- ecm_admin.label_definitions  (NEW in v5.0)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.label_definitions (
    id              SERIAL       PRIMARY KEY,
    label_key       VARCHAR(100) NOT NULL UNIQUE,
    display_name    VARCHAR(255) NOT NULL,
    input_type      VARCHAR(20)  NOT NULL DEFAULT 'FREE_TEXT',
    allowed_values  JSONB,
    is_system       BOOLEAN      NOT NULL DEFAULT FALSE,
    is_required     BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_label_input_type CHECK (input_type IN ('FREE_TEXT', 'DROPDOWN'))
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.watch_folder_config  (NEW in v5.0)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.watch_folder_config (
    id              SERIAL       PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL DEFAULT 'default',
    folder_path     VARCHAR(500) NOT NULL,
    poll_interval   INTEGER      NOT NULL DEFAULT 300,
    processed_path  VARCHAR(500),
    failed_path     VARCHAR(500),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, folder_path)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- Customer profile schema for CRM-populated form fill (Salesforce integration).
-- See design note "CRM-Aware Form Fill & Customer 360" (2026-07-17).
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_admin.customer_profile_attributes (
    id          SERIAL PRIMARY KEY,
    key         VARCHAR(100)  NOT NULL UNIQUE,
    label       VARCHAR(200)  NOT NULL,
    value_type  VARCHAR(30)   NOT NULL DEFAULT 'STRING',
    source      VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    -- Comma-separated segment codes (RETAIL,SMB,COMMERCIAL); null/blank = all segments.
    segments    VARCHAR(100),
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_profile_attr_source CHECK (source IN ('MANUAL', 'CRM_MAPPED')),
    CONSTRAINT ck_profile_attr_value_type CHECK (value_type IN ('STRING', 'DATE', 'EMAIL', 'PHONE', 'NUMBER'))
);

CREATE TABLE ecm_admin.customer_profile_attribute_mappings (
    id                SERIAL PRIMARY KEY,
    attribute_id      INTEGER NOT NULL UNIQUE
                        REFERENCES ecm_admin.customer_profile_attributes(id) ON DELETE CASCADE,
    salesforce_object VARCHAR(100) NOT NULL,
    salesforce_field  VARCHAR(150) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ecm_admin.customer_relationship_types (
    id                       SERIAL PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL UNIQUE,
    salesforce_object        VARCHAR(100) NOT NULL,
    salesforce_parent_field  VARCHAR(150) NOT NULL,
    -- Salesforce object holding the customer's OWN record (Contact for Retail,
    -- Account for SMB/Commercial) — resolved first since salesforce_parent_field
    -- on the child object is a lookup storing a Salesforce Id, not the
    -- customer's external ref string.
    parent_object            VARCHAR(100) NOT NULL DEFAULT 'Contact',
    -- Comma-separated segment codes (RETAIL,SMB,COMMERCIAL); null/blank = all segments.
    segments                 VARCHAR(100),
    sort_order               INTEGER NOT NULL DEFAULT 0,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ecm_admin.customer_relationship_attributes (
    id                    SERIAL PRIMARY KEY,
    relationship_type_id  INTEGER NOT NULL
                            REFERENCES ecm_admin.customer_relationship_types(id) ON DELETE CASCADE,
    key                   VARCHAR(100) NOT NULL,
    label                 VARCHAR(200) NOT NULL,
    value_type            VARCHAR(30) NOT NULL DEFAULT 'STRING',
    salesforce_field      VARCHAR(150) NOT NULL,
    sort_order            INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rel_attr_key UNIQUE (relationship_type_id, key),
    CONSTRAINT ck_rel_attr_value_type CHECK (value_type IN ('STRING', 'DATE', 'EMAIL', 'PHONE', 'NUMBER'))
);

CREATE INDEX idx_profile_attr_mappings_attr ON ecm_admin.customer_profile_attribute_mappings(attribute_id);
CREATE INDEX idx_rel_attrs_type ON ecm_admin.customer_relationship_attributes(relationship_type_id);

-- Per-customer values for MANUAL-source profile attributes.
-- party_id is a soft ref → ecm_core.parties.id (cross-schema, no FK).
CREATE TABLE ecm_admin.customer_profile_values (
    id           SERIAL PRIMARY KEY,
    party_id     UUID NOT NULL,
    attribute_id INTEGER NOT NULL REFERENCES ecm_admin.customer_profile_attributes(id) ON DELETE CASCADE,
    value        TEXT,
    updated_by   VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_profile_value UNIQUE (party_id, attribute_id)
);
CREATE INDEX idx_profile_values_party ON ecm_admin.customer_profile_values(party_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_admin.departments  (convenience view over ecm_core.departments)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE VIEW ecm_admin.departments AS
SELECT id, name, code, parent_id, is_active, created_at, updated_at
FROM ecm_core.departments;


-- ═══════════════════════════════════════════════════════════════════════════════
--  ECM_WORKFLOW
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow.workflow_groups
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
-- ecm_workflow.workflow_group_members
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_group_members (
    id       SERIAL  PRIMARY KEY,
    group_id INTEGER NOT NULL REFERENCES ecm_workflow.workflow_groups(id) ON DELETE CASCADE,
    user_id  INTEGER NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (group_id, user_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow.workflow_definition_configs
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
-- ecm_workflow.workflow_instance_records
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.workflow_instance_records (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    process_instance_id    VARCHAR(100) NOT NULL UNIQUE,
    document_id            UUID,
    document_name          VARCHAR(500),
    category_id            INTEGER,
    workflow_definition_id INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_definition_configs(id),
    status                 VARCHAR(30)  NOT NULL DEFAULT 'ACTIVE',
    trigger_type           VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    started_by_subject     VARCHAR(255) NOT NULL,
    started_by_email       VARCHAR(255),
    template_id            INTEGER,
    submission_id          VARCHAR(100),
    case_id                UUID,
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
-- ecm_workflow.workflow_templates  (includes tags column from V2 migration)
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
    tags                    TEXT[],
    created_by              VARCHAR(200),
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow.category_workflow_mappings  (from V3 migration)
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_workflow.category_workflow_mappings (
    id           SERIAL       PRIMARY KEY,
    category_id  INTEGER      NOT NULL,
    template_id  INTEGER      NOT NULL REFERENCES ecm_workflow.workflow_templates(id) ON DELETE CASCADE,
    is_active    BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(200),
    CONSTRAINT uq_category_workflow_mapping UNIQUE (category_id)
);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_workflow.workflow_sla_tracking
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
-- ecm_workflow.workflow_task_history
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
--  ECM_FORMS
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms.form_definitions
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
    document_category_id INTEGER,
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

-- Note: form field ↔ customer-attribute binding (Layer 3 of CRM-aware form
-- fill) lives INSIDE form_definitions.schema (JSONB) as FormField.customerAttributeKey
-- — same place as every other field property (label, options, validation).
-- No separate table needed; it's saved along with the rest of the form.

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_forms.form_submissions
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_forms.form_submissions (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
    form_definition_id    UUID         NOT NULL REFERENCES ecm_forms.form_definitions(id),
    form_key              VARCHAR(200) NOT NULL,
    form_version          INTEGER      NOT NULL,
    form_schema_snapshot  JSONB,
    submission_data       JSONB,
    party_id              UUID,
    party_display_name    VARCHAR(255),
    party_external_id     VARCHAR(100),
    status                VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    submitted_by          VARCHAR(255) NOT NULL,
    submitted_by_name     VARCHAR(255),
    submitted_at          TIMESTAMPTZ,
    docusign_envelope_id  VARCHAR(255),
    docusign_status       VARCHAR(100),
    docusign_sent_at      TIMESTAMPTZ,
    docusign_completed_at TIMESTAMPTZ,
    signed_document_id    UUID,
    draft_document_id     UUID,
    workflow_instance_id  VARCHAR(255),
    assigned_to           VARCHAR(255),
    assigned_at           TIMESTAMPTZ,
    review_notes          TEXT,
    reviewed_by           VARCHAR(255),
    reviewed_at           TIMESTAMPTZ,
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
-- ecm_forms.docusign_events
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
--  ECM_BATCH  (NEW in v5.0)
-- ═══════════════════════════════════════════════════════════════════════════════

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_batch.batch_jobs
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_batch.batch_jobs (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    status          VARCHAR(30)  NOT NULL DEFAULT 'CREATED',
    source          VARCHAR(30)  NOT NULL DEFAULT 'MANUAL_UPLOAD',
    total_items     INTEGER      NOT NULL DEFAULT 0,
    processed_items INTEGER      NOT NULL DEFAULT 0,
    auto_filed      INTEGER      NOT NULL DEFAULT 0,
    sent_to_review  INTEGER      NOT NULL DEFAULT 0,
    failed_items    INTEGER      NOT NULL DEFAULT 0,
    notes           TEXT,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ck_batch_status CHECK (status IN ('CREATED','QUEUED','PROCESSING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
    CONSTRAINT ck_batch_source CHECK (source IN ('MANUAL_UPLOAD','WATCH_FOLDER','MIGRATION','AUTO_CLASSIFY'))
);
CREATE INDEX idx_batch_jobs_status     ON ecm_batch.batch_jobs(status);
CREATE INDEX idx_batch_jobs_created_by ON ecm_batch.batch_jobs(created_by);
CREATE INDEX idx_batch_jobs_created_at ON ecm_batch.batch_jobs(created_at DESC);

-- ─────────────────────────────────────────────────────────────────────────────
-- ecm_batch.batch_items
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE ecm_batch.batch_items (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id                UUID         NOT NULL REFERENCES ecm_batch.batch_jobs(id) ON DELETE CASCADE,
    document_id             UUID,
    original_filename       VARCHAR(500) NOT NULL,
    status                  VARCHAR(30)  NOT NULL DEFAULT 'QUEUED',
    -- Classification results
    detected_category_id    INTEGER,
    category_confidence     DECIMAL(5,2),
    -- Customer matching results
    detected_customer_id    UUID,
    customer_confidence     DECIMAL(5,2),
    extracted_name          VARCHAR(255),
    extracted_account_no    VARCHAR(100),
    extracted_dob           DATE,
    extracted_address       TEXT,
    candidate_customer_ids  JSONB,
    -- Review
    reviewed_by             VARCHAR(255),
    reviewed_at             TIMESTAMPTZ,
    final_category_id       INTEGER,
    final_customer_id       UUID,
    review_notes            TEXT,
    -- Error
    error_message           TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_batch_item_status CHECK (status IN ('QUEUED','PROCESSING','AUTO_FILED','IN_REVIEW','REVIEW_COMPLETE','FAILED'))
);
CREATE INDEX idx_batch_items_batch    ON ecm_batch.batch_items(batch_id);
CREATE INDEX idx_batch_items_status   ON ecm_batch.batch_items(status);
CREATE INDEX idx_batch_items_document ON ecm_batch.batch_items(document_id);
CREATE INDEX idx_batch_items_review   ON ecm_batch.batch_items(status) WHERE status = 'IN_REVIEW';
