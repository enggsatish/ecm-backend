SET search_path TO ecm_admin;

ALTER TABLE integration_configs
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(100) NOT NULL DEFAULT '';

INSERT INTO integration_configs (tenant_id, system_key, display_name, enabled, config, secrets)
VALUES (
           'default',
           'DOCUSIGN',
           'DocuSign eSignature',
           FALSE,
           '{"base_url":"https://demo.docusign.net","auth_server":"https://account-d.docusign.com","account_id":"","integration_key":"","impersonated_user_id":""}'::jsonb,
           '{}'::jsonb
       )
    ON CONFLICT (tenant_id, system_key) DO UPDATE
                                               SET display_name = EXCLUDED.display_name
                                           WHERE integration_configs.display_name = '';