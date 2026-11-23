package io.github.shubhajit93.campaign.service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * SKIP LOCKED lets several replicas relay the outbox in parallel without publishing
     * the same row twice or blocking each other.
     */
    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

    long countByPublishedAtIsNull();
}
