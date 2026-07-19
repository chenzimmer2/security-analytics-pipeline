package com.akamai.miniwsa.stats.service;

import com.akamai.miniwsa.stats.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Runs all aggregation queries directly in the DB layer.
 * Moving GROUP BY / AVG / COUNT to SQL means only a small result set
 * travels to the app, not thousands of raw rows.
 *
 * All queries share a single WhereClause built once per request,
 * so the filter logic lives in exactly one place.
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public SummaryResponse getSummary(Long configId, Instant from, Instant to) {
        WhereClause where = buildWhere(configId, from, to);

        return SummaryResponse.builder()
                .configId(configId)
                .timeRange(SummaryResponse.TimeRange.builder().from(from).to(to).build())
                .totalEvents(queryTotalEvents(where))
                .byCategory(queryByCategory(where))
                .byAction(queryByAction(where))
                .topAttackers(queryTopAttackers(where))
                .topTargetedPaths(queryTopTargetedPaths(where))
                .build();
    }

    // -------------------------------------------------------------------------
    // Individual aggregation queries
    // -------------------------------------------------------------------------

    private long queryTotalEvents(WhereClause where) {
        String sql = "SELECT COUNT(*) FROM security_events" + where.clause();
        Long count = jdbc.queryForObject(sql, Long.class, where.args());
        return count != null ? count : 0L;
    }

    private Map<String, CategoryStats> queryByCategory(WhereClause where) {
        String sql = "SELECT rule_category, COUNT(*), AVG(threat_score) "
                + "FROM security_events" + where.clause()
                + " GROUP BY rule_category";

        Map<String, CategoryStats> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            result.put(
                    rs.getString(1),
                    CategoryStats.builder()
                            .count(rs.getLong(2))
                            .avgThreatScore(round1(rs.getDouble(3)))
                            .build()
            );
        }, where.args());
        return result;
    }

    private Map<String, Long> queryByAction(WhereClause where) {
        String sql = "SELECT action, COUNT(*) FROM security_events" + where.clause()
                + " GROUP BY action";

        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getLong(2)), where.args());
        return result;
    }

    private List<AttackerStats> queryTopAttackers(WhereClause where) {
        String sql = "SELECT client_ip, COUNT(*), AVG(threat_score) "
                + "FROM security_events" + where.clause()
                + " GROUP BY client_ip ORDER BY COUNT(*) DESC LIMIT 10";

        return jdbc.query(sql, (rs, rowNum) -> AttackerStats.builder()
                .clientIp(rs.getString(1))
                .count(rs.getLong(2))
                .avgThreatScore(round1(rs.getDouble(3)))
                .build(), where.args());
    }

    private List<PathStats> queryTopTargetedPaths(WhereClause where) {
        String sql = "SELECT path, COUNT(*) FROM security_events" + where.clause()
                + " GROUP BY path ORDER BY COUNT(*) DESC LIMIT 10";

        return jdbc.query(sql, (rs, rowNum) -> PathStats.builder()
                .path(rs.getString(1))
                .count(rs.getLong(2))
                .build(), where.args());
    }

    // -------------------------------------------------------------------------
    // WHERE clause builder
    // -------------------------------------------------------------------------

    /**
     * Builds a shared, parameterised WHERE clause from the optional filter params.
     * Conditions are appended only when the corresponding param is non-null,
     * so queries are correct whether 0, 1, or all filters are present.
     */
    private WhereClause buildWhere(Long configId, Instant from, Instant to) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (configId != null) {
            conditions.add("config_id = ?");
            params.add(configId);
        }
        if (from != null) {
            conditions.add("event_timestamp >= ?");
            params.add(Timestamp.from(from));
        }
        if (to != null) {
            conditions.add("event_timestamp <= ?");
            params.add(Timestamp.from(to));
        }

        String clause = conditions.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", conditions);

        return new WhereClause(clause, params.toArray());
    }

    /** Rounds a double to one decimal place, matching the assignment response format. */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record WhereClause(String clause, Object[] args) {}
}
