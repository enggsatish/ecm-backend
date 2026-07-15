-- Add classification keywords to document categories.
-- Admins can configure per-category keywords via the UI.
-- The classifier falls back to hardcoded defaults when this column is empty.
ALTER TABLE ecm_admin.document_categories
    ADD COLUMN IF NOT EXISTS classification_keywords TEXT[];

-- Seed default keywords for well-known categories
UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'driver','license','licence','passport','date of birth','expiry','expires',
    'identification','id card','social security','operator''s licence',
    'operator''s license','document number','nationality','class:'
] WHERE code = 'IDENTITY';

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'boarding pass','boarding','flight','departure','arrival','gate',
    'seat','airline','passenger','terminal','operated by'
] WHERE code = 'BOARDINGPASS';

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'mortgage','loan amount','borrower','property address','interest rate','deed of trust','lien'
] WHERE code = 'MORTGAGE';

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'statement','balance','account number','transaction','account summary','deposit','withdrawal'
] WHERE code = 'FINANCIAL';

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'invoice','amount due','bill to','payment terms','billing','total due','remittance'
] WHERE code IN ('INV', 'INVOICE');

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'agreement','contract','parties','obligations','signature','witness','notary','covenant'
] WHERE code IN ('LEGAL', 'CTR');

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'aml','kyc','compliance','regulatory','verification','due diligence','sanctions'
] WHERE code = 'COMPLIANCE';

UPDATE ecm_admin.document_categories SET classification_keywords = ARRAY[
    'resume','curriculum vitae','work experience','education','skills','objective','references'
] WHERE code = 'RESUME';
