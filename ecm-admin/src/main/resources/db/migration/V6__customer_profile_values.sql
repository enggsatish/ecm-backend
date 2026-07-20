-- Per-customer values for MANUAL-source profile attributes (Layer 1).
-- CRM_MAPPED attributes never land here — their values are always fetched
-- live from Salesforce. party_id is a soft ref → ecm_core.parties.id
-- (cross-schema, no FK, same convention as documents.party_external_id).

CREATE TABLE IF NOT EXISTS ecm_admin.customer_profile_values (
    id           SERIAL PRIMARY KEY,
    party_id     UUID NOT NULL,
    attribute_id INTEGER NOT NULL REFERENCES ecm_admin.customer_profile_attributes(id) ON DELETE CASCADE,
    value        TEXT,
    updated_by   VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_profile_value UNIQUE (party_id, attribute_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_values_party ON ecm_admin.customer_profile_values(party_id);
