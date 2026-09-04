CREATE TABLE tb_outbox_event (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    last_error VARCHAR(1000),
    CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_event_status_created_at
    ON tb_outbox_event (status, created_at);
