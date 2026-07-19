package com.akamai.miniwsa.samples.service;

import com.akamai.miniwsa.samples.dto.SamplesResponse;
import com.akamai.miniwsa.samples.dto.SecurityEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes two queries per request — both sharing the same WHERE clause:
 *   1. COUNT(*) to populate the total for pagination metadata.
 *   2. SELECT * with ORDER BY + LIMIT + OFFSET to populate the data page.
 *
 * Keeping both queries consistent is critical: the total must reflect
 * the same filter set as the page being returned.
 */
@Service
@RequiredArgsConstructor
public class SamplesService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT     = 100;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public SamplesResponse getSamples(
            Long configId, Instant from, Instant to,
            String category, String action,
            int limit, int offset) {

        WhereClause where = buildWhere(configId, from, to, category, action);

        long total = queryCount(where);
        List<SecurityEventResponse> data = queryPage(where, limit, offset);

        return SamplesResponse.builder()
                .total(total)
                .data(data)
                .build();
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    private long queryCount(WhereClause where) {
        String sql = "SELECT COUNT(*) FROM security_events" + where.clause();
        Long count = jdbc.queryForObject(sql, Long.class, where.args());
        return count != null ? count : 0L;
    }

    private List<SecurityEventResponse> queryPage(WhereClause where, int limit, int offset) {
        String sql = "SELECT event_id, event_timestamp, received_at, config_id, policy_id, "
                + "client_ip, hostname, path, method, status_code, user_agent, "
                + "rule_id, rule_name, rule_severity, rule_category, action, "
                + "geo_country, geo_city, request_size, response_size, "
                + "attack_type, threat_score "
                + "FROM security_events"
                + where.clause()
                + " ORDER BY event_timestamp DESC"
                + " LIMIT ? OFFSET ?";

        List<Object> params = new ArrayList<>(List.of(where.args()));
        params.add(limit);
        params.add(offset);

        return jdbc.query(sql, EVENT_ROW_MAPPER, params.toArray());
    }

    // -------------------------------------------------------------------------
    // WHERE clause builder
    // -------------------------------------------------------------------------

    private WhereClause buildWhere(Long configId, Instant from, Instant to,
                                   String category, String action) {
        List<String> conditions = new ArrayList<>();
        List<Object> params     = new ArrayList<>();

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
        if (category != null) {
            conditions.add("rule_category = ?");
            params.add(category.toUpperCase());
        }
        if (action != null) {
            conditions.add("action = ?");
            params.add(action.toUpperCase());
        }

        String clause = conditions.isEmpty()
                ? ""
                : " WHERE " + String.join(" AND ", conditions);

        return new WhereClause(clause, params.toArray());
    }

    // -------------------------------------------------------------------------
    // RowMapper
    // -------------------------------------------------------------------------

    private static final RowMapper<SecurityEventResponse> EVENT_ROW_MAPPER = (rs, rowNum) ->
            SecurityEventResponse.builder()
                    .eventId(rs.getString("event_id"))
                    .eventTimestamp(toInstant(rs.getTimestamp("event_timestamp")))
                    .receivedAt(toInstant(rs.getTimestamp("received_at")))
                    .configId(rs.getLong("config_id"))
                    .policyId(rs.getString("policy_id"))
                    .clientIp(rs.getString("client_ip"))
                    .hostname(rs.getString("hostname"))
                    .path(rs.getString("path"))
                    .method(rs.getString("method"))
                    .statusCode(rs.getInt("status_code"))
                    .userAgent(rs.getString("user_agent"))
                    .ruleId(rs.getString("rule_id"))
                    .ruleName(rs.getString("rule_name"))
                    .ruleSeverity(rs.getString("rule_severity"))
                    .ruleCategory(rs.getString("rule_category"))
                    .action(rs.getString("action"))
                    .geoCountry(rs.getString("geo_country"))
                    .geoCity(rs.getString("geo_city"))
                    .requestSize(rs.getInt("request_size"))
                    .responseSize(rs.getInt("response_size"))
                    .attackType(rs.getString("attack_type"))
                    .threatScore(rs.getInt("threat_score"))
                    .build();

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private record WhereClause(String clause, Object[] args) {}
}
