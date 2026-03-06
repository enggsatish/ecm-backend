-- ══════════════════════════════════════════════════════════════════
-- V2__add_hierarchy.sql
-- Adds financial segment and product-line hierarchy to ecm_admin.
-- Segment:      Retail | Commercial | Small Business
-- Product Line: Banking | Savings | Loan | Investment | Mutual Fund
-- ══════════════════════════════════════════════════════════════════

-- Business segments (top of the financial hierarchy)
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

-- Product lines within a segment
CREATE TABLE ecm_admin.product_lines (
                                         id          SERIAL       PRIMARY KEY,
                                         segment_id  INTEGER      NOT NULL REFERENCES ecm_admin.segments(id) ON DELETE RESTRICT,
                                         name        VARCHAR(100) NOT NULL,
                                         code        VARCHAR(30)  NOT NULL UNIQUE,  -- 'RETAIL_LOAN', 'COMMERCIAL_BANKING'
                                         description TEXT,
                                         is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
                                         created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                                         updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pl_segment ON ecm_admin.product_lines(segment_id);
CREATE INDEX idx_pl_active  ON ecm_admin.product_lines(is_active);

-- Add optional segment and product-line to existing document categories
ALTER TABLE ecm_admin.document_categories
    ADD COLUMN segment_id      INTEGER REFERENCES ecm_admin.segments(id),
    ADD COLUMN product_line_id INTEGER REFERENCES ecm_admin.product_lines(id);

-- Add optional segment and product-line to existing products
ALTER TABLE ecm_admin.products
    ADD COLUMN segment_id      INTEGER REFERENCES ecm_admin.segments(id),
    ADD COLUMN product_line_id INTEGER REFERENCES ecm_admin.product_lines(id);

-- External product reference table (third-party integrations — mutual funds, etc.)
CREATE TABLE ecm_admin.external_product_refs (
                                                 id              SERIAL       PRIMARY KEY,
                                                 product_id      INTEGER      NOT NULL REFERENCES ecm_admin.products(id) ON DELETE CASCADE,
                                                 external_system VARCHAR(50)  NOT NULL,   -- 'BLOOMBERG', 'MORNINGSTAR', 'CORE_BANKING'
                                                 external_id     VARCHAR(200) NOT NULL,
                                                 sync_at         TIMESTAMPTZ,
                                                 UNIQUE (product_id, external_system)
);
CREATE INDEX idx_extref_product ON ecm_admin.external_product_refs(product_id);

-- Seed segments
INSERT INTO ecm_admin.segments (name, code, description) VALUES
                                                             ('Retail',         'RETAIL',     'Retail banking customers — individuals and households'),
                                                             ('Commercial',     'COMMERCIAL', 'Commercial banking — mid-market and enterprise'),
                                                             ('Small Business', 'SMB',        'Small business banking — sole traders and small enterprises');

-- Seed product lines
INSERT INTO ecm_admin.product_lines (segment_id, name, code, description) VALUES
                                                                              (1, 'Banking',      'RETAIL_BANKING',     'Retail current, savings, and chequing accounts'),
                                                                              (1, 'Loans',        'RETAIL_LOANS',       'Retail mortgages, auto loans, personal loans'),
                                                                              (1, 'Investment',   'RETAIL_INVESTMENT',  'Retail term deposits and investment accounts'),
                                                                              (1, 'Mutual Funds', 'RETAIL_MUTUAL_FUNDS','Third-party mutual fund distribution'),
                                                                              (2, 'Banking',      'COMM_BANKING',       'Commercial transactional accounts'),
                                                                              (2, 'Lending',      'COMM_LENDING',       'Commercial credit facilities and trade finance'),
                                                                              (3, 'Banking',      'SMB_BANKING',        'Small business accounts'),
                                                                              (3, 'Loans',        'SMB_LOANS',          'Small business lending');