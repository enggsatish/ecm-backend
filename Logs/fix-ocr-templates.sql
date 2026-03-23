-- Fix OCR template regex patterns for eForm-generated PDFs
--
-- eForm PDFs render field labels on one line, values on the next line.
-- After the TesseractHttpClient parseText() fix, extracted text now has
-- real newline characters (not literal \n strings).
--
-- Pattern strategy: label → optional colon/space → newline(s) → capture value

UPDATE ecm_admin.ocr_templates
SET fields = jsonb_build_array(
  jsonb_build_object(
    'fieldName', 'borrower_name',
    'pattern', '(?i)(?:borrower\s*name|applicant|full\s*name)[:\s]*\n+\s*([A-Za-z][A-Za-z .,-]+?)(?:\n|$)',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'last_name',
    'pattern', '(?i)(?:last\s*name|surname)[:\s]*\n+\s*([A-Za-z][A-Za-z .,-]+?)(?:\n|$)',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'loan_amount',
    'pattern', '(?i)(?:loan\s*amount|principal)[:\s]*\n+\s*\$?([\d,.]+)',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'interest_rate',
    'pattern', '(?i)(?:interest\s*rate)[:\s]*\n+\s*([\d.]+)\s*%?',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'property_address',
    'pattern', '(?i)(?:property\s*address|address)[:\s]*\n+\s*([A-Za-z0-9][A-Za-z0-9 ,.\-]+?)(?:\n|$)',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'loan_term_years',
    'pattern', '(?i)(?:loan\s*term|term)[:\s]*\n+\s*(\d+)\s*(?:year|yr|month|mo)?',
    'defaultValue', ''
  )
)
WHERE category_code = 'MORTGAGE';

UPDATE ecm_admin.ocr_templates
SET fields = jsonb_build_array(
  jsonb_build_object(
    'fieldName', 'full_name',
    'pattern', '(?i)(?:name|full\s*name)[:\s]*\n+\s*([A-Za-z][A-Za-z .,-]+?)(?:\n|$)',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'document_number',
    'pattern', '(?i)(?:licence\s*no|license\s*no|dl\s*no|passport\s*no|id\s*no|document\s*no)[:\s#]*\n?\s*([A-Z0-9]{5,15})',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'date_of_birth',
    'pattern', '(?i)(?:date\s*of\s*birth|dob|born)[:\s]*\n?\s*(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4})',
    'defaultValue', ''
  ),
  jsonb_build_object(
    'fieldName', 'expiry_date',
    'pattern', '(?i)(?:expiry|expires|expiration|exp)[:\s]*\n?\s*(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4})',
    'defaultValue', ''
  )
)
WHERE category_code = 'IDENTITY';

SELECT category_code, name FROM ecm_admin.ocr_templates WHERE category_code IN ('MORTGAGE', 'IDENTITY');
