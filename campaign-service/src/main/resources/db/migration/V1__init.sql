create table campaigns
(
    id                  uuid primary key,
    name                varchar(200) not null,
    template            text         not null,
    status              varchar(20)  not null,
    expected_deliveries integer      not null default 0,
    delivered           integer      not null default 0,
    failed              integer      not null default 0,
    created_at          timestamptz  not null,
    launched_at         timestamptz,
    completed_at        timestamptz
);

create table campaign_channels
(
    campaign_id uuid        not null references campaigns (id) on delete cascade,
    channel     varchar(20) not null,
    primary key (campaign_id, channel)
);

create table recipients
(
    id          uuid primary key,
    campaign_id uuid         not null references campaigns (id) on delete cascade,
    name        varchar(200) not null,
    email       varchar(320),
    phone       varchar(20)
);

create index idx_recipients_campaign on recipients (campaign_id);

-- Transactional outbox: written in the same transaction as the business change, relayed to SQS afterwards.
create table outbox_events
(
    id           uuid primary key,
    aggregate_id uuid         not null,
    destination  varchar(80)  not null,
    payload      text         not null,
    created_at   timestamptz  not null,
    published_at timestamptz
);

-- Partial index: the relay only ever scans unpublished rows.
create index idx_outbox_unpublished on outbox_events (created_at) where published_at is null;

-- Idempotent consumer: one row per consumed message id.
create table processed_messages
(
    message_id   uuid primary key,
    processed_at timestamptz not null
);
