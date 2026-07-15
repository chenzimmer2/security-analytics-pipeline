CREATE TABLE security_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        VARCHAR(100) UNIQUE NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    config_id       BIGINT NOT NULL,
    policy_id       VARCHAR(100),
    client_ip       VARCHAR(45) NOT NULL,
    hostname        VARCHAR(255),
    path            TEXT,
    method          VARCHAR(10),
    status_code     INTEGER,
    user_agent      TEXT,
    rule_id         VARCHAR(50),
    rule_name       VARCHAR(100),
    rule_severity   VARCHAR(20),
    rule_category   VARCHAR(50),
    action          VARCHAR(20),
    geo_country     VARCHAR(10),
    geo_city        VARCHAR(100),
    request_size    INTEGER,
    response_size   INTEGER,
    attack_type     VARCHAR(100),
    threat_score    INTEGER
);

-- Stats / samples time-range filter: WHERE config_id = ? AND event_timestamp BETWEEN ? AND ?
CREATE INDEX idx_se_config_timestamp ON security_events (config_id, event_timestamp);

-- Repeat-offender detection: WHERE client_ip = ? AND received_at > now() - interval '10 minutes'
CREATE INDEX idx_se_ip_received ON security_events (client_ip, received_at);

-- Category filter + byCategory stats aggregation
CREATE INDEX idx_se_category_timestamp ON security_events (rule_category, event_timestamp);

-- Action filter + byAction stats aggregation
CREATE INDEX idx_se_action_timestamp ON security_events (action, event_timestamp);
