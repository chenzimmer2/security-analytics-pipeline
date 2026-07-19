package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.SecurityEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SecurityEventRepository extends JpaRepository<SecurityEvent, Long> {

    /**
     * Count events received from the given IP in the last 10 minutes,
     * using server-assigned received_at to prevent timestamp manipulation.
     */
    @Query("SELECT COUNT(e) FROM SecurityEvent e WHERE e.clientIp = :ip AND e.receivedAt > :since")
    long countByClientIpAndReceivedAtAfter(@Param("ip") String ip, @Param("since") Instant since);
}
