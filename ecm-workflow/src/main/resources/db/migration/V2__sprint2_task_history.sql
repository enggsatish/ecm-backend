SET search_path TO ecm_workflow;

CREATE TABLE IF NOT EXISTS workflow_task_history (
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

ALTER TABLE workflow_task_history
    ADD COLUMN IF NOT EXISTS process_instance_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS document_id         UUID,
    ADD COLUMN IF NOT EXISTS sla_deadline        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at          TIMESTAMPTZ DEFAULT NOW();

ALTER TABLE workflow_task_history
    ALTER COLUMN process_instance_id SET NOT NULL;

DROP INDEX IF EXISTS idx_wth_instance;
DROP INDEX IF EXISTS idx_wth_actor;
DROP INDEX IF EXISTS idx_wth_acted_at;

CREATE INDEX IF NOT EXISTS idx_task_history_task_id     ON workflow_task_history (task_id);
CREATE INDEX IF NOT EXISTS idx_task_history_process_id  ON workflow_task_history (process_instance_id);
CREATE INDEX IF NOT EXISTS idx_task_history_actor       ON workflow_task_history (actor_subject);
CREATE INDEX IF NOT EXISTS idx_task_history_created_at  ON workflow_task_history (created_at DESC);