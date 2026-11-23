package io.github.shubhajit93.campaign.worker.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Idempotency guard. Called inside the delivery transaction, so a failed attempt rolls the mark back. */
@Component
public class ProcessedMessageStore {

    private final JdbcTemplate jdbc;

    public ProcessedMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return {@code true} the first time a message id is seen, {@code false} for a duplicate */
    public boolean markProcessed(UUID messageId, Instant now) {
        return jdbc.update("insert into processed_messages (message_id, processed_at) values (?, ?) "
                + "on conflict (message_id) do nothing", messageId, Timestamp.from(now)) == 1;
    }
}
