-- Idempotent consumer: a delivery is processed at most once per message id.
create table processed_messages
(
    message_id   uuid primary key,
    processed_at timestamptz not null
);
