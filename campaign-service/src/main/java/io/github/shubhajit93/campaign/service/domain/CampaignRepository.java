package io.github.shubhajit93.campaign.service.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CampaignRepository extends JpaRepository<Campaign, UUID> {

    /** Row lock, so two concurrent launch requests cannot both fan out the same campaign. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Campaign c where c.id = :id")
    Optional<Campaign> findByIdForUpdate(@Param("id") UUID id);

    // Counters are incremented in SQL rather than read-modify-write, so parallel consumers never lose an update.
    @Modifying
    @Query("update Campaign c set c.delivered = c.delivered + 1 where c.id = :id")
    int incrementDelivered(@Param("id") UUID id);

    @Modifying
    @Query("update Campaign c set c.failed = c.failed + 1 where c.id = :id")
    int incrementFailed(@Param("id") UUID id);

    @Modifying
    @Query("""
            update Campaign c set c.status = :completed, c.completedAt = :now
            where c.id = :id and c.status = :launched and c.delivered + c.failed >= c.expectedDeliveries
            """)
    int completeIfFinished(@Param("id") UUID id, @Param("now") Instant now,
                           @Param("launched") CampaignStatus launched,
                           @Param("completed") CampaignStatus completed);
}
