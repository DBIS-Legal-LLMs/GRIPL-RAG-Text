CREATE TABLE evaluation_run (
    id          BIGSERIAL PRIMARY KEY,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    completed_at TIMESTAMP WITH TIME ZONE,
    status      VARCHAR(20) NOT NULL DEFAULT 'in_progress'
);

CREATE TABLE evaluation_run_event (
    id      BIGSERIAL PRIMARY KEY,
    run_id  BIGINT NOT NULL REFERENCES evaluation_run(id) ON DELETE CASCADE,
    payload JSONB  NOT NULL
);

CREATE INDEX idx_evaluation_run_event_run_id ON evaluation_run_event(run_id);
