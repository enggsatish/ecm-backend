-- Segment-scoping for CRM-aware form fill (design follow-up, 2026-07-19):
-- profile attributes and relationship types were global, so Retail customers
-- showed permanently-blank Business Name/Segment rows and vice versa for
-- SMB/Commercial. NULL/blank segments = applies to all segments (existing
-- rows keep working unchanged).
--
-- parent_object is new for Tier B: the Salesforce object holding the
-- customer's OWN record (Contact for Retail, Account for SMB/Commercial),
-- needed to resolve the customer's Salesforce Id before querying the child
-- relationship object's lookup field (which stores a Salesforce Id, not the
-- customer's external ref string).

ALTER TABLE ecm_admin.customer_profile_attributes
    ADD COLUMN IF NOT EXISTS segments VARCHAR(100);

ALTER TABLE ecm_admin.customer_relationship_types
    ADD COLUMN IF NOT EXISTS segments VARCHAR(100),
    ADD COLUMN IF NOT EXISTS parent_object VARCHAR(100) NOT NULL DEFAULT 'Contact';
