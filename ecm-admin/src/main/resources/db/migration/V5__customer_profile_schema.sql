-- Customer profile schema for CRM-populated form fill (Salesforce integration).
-- See design note "CRM-Aware Form Fill & Customer 360" (2026-07-17).
--
-- Layer 1 — superadmin-defined canonical profile attributes, extending the
-- existing party/customer entity (ecm_core.parties) as a purely additive layer.
-- Layer 2 (CRM mapping) and the Tier-B relationship tables (memberships,
-- accounts) live alongside it in this same migration.

CREATE TABLE IF NOT EXISTS ecm_admin.customer_profile_attributes (
    id          SERIAL PRIMARY KEY,
    key         VARCHAR(100)  NOT NULL UNIQUE,
    label       VARCHAR(200)  NOT NULL,
    value_type  VARCHAR(30)   NOT NULL DEFAULT 'STRING',
    source      VARCHAR(20)   NOT NULL DEFAULT 'MANUAL',
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_profile_attr_source CHECK (source IN ('MANUAL', 'CRM_MAPPED')),
    CONSTRAINT ck_profile_attr_value_type CHECK (value_type IN ('STRING', 'DATE', 'EMAIL', 'PHONE', 'NUMBER'))
);

-- One mapping per attribute (v1: single Salesforce field per canonical attribute).
CREATE TABLE IF NOT EXISTS ecm_admin.customer_profile_attribute_mappings (
    id                SERIAL PRIMARY KEY,
    attribute_id      INTEGER NOT NULL UNIQUE
                        REFERENCES ecm_admin.customer_profile_attributes(id) ON DELETE CASCADE,
    salesforce_object VARCHAR(100) NOT NULL,
    salesforce_field  VARCHAR(150) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Tier B — one-to-many CRM relationships (a customer can have several
-- memberships, accounts, etc). Each type carries its own attribute schema.
CREATE TABLE IF NOT EXISTS ecm_admin.customer_relationship_types (
    id                       SERIAL PRIMARY KEY,
    name                     VARCHAR(100) NOT NULL UNIQUE,
    salesforce_object        VARCHAR(100) NOT NULL,
    salesforce_parent_field  VARCHAR(150) NOT NULL,
    sort_order               INTEGER NOT NULL DEFAULT 0,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ecm_admin.customer_relationship_attributes (
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

CREATE INDEX IF NOT EXISTS idx_profile_attr_mappings_attr ON ecm_admin.customer_profile_attribute_mappings(attribute_id);
CREATE INDEX IF NOT EXISTS idx_rel_attrs_type ON ecm_admin.customer_relationship_attributes(relationship_type_id);
