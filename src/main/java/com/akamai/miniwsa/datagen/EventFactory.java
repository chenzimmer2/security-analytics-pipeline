package com.akamai.miniwsa.datagen;

import com.akamai.miniwsa.ingestion.dto.GeoLocationDto;
import com.akamai.miniwsa.ingestion.dto.RuleDto;
import com.akamai.miniwsa.ingestion.dto.SecurityEventRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds randomized but schema-valid {@link SecurityEventRequest} objects.
 *
 * Field distributions are weighted to produce a realistic dataset:
 *  - INJECTION and BOT events dominate (real-world SOC observation).
 *  - DENY is the most common action.
 *  - ~35 % of generated events hit admin/login paths to trigger the +15 path bonus.
 *
 * All generated events will pass Jakarta Bean Validation — no batch will be
 * rejected with 400 due to invalid event data.
 */
@Component
@Profile("datagen")
public class EventFactory {

    // ── Config IDs ────────────────────────────────────────────────────────────

    private static final List<Long>   CONFIG_IDS  = List.of(14227L, 14228L, 14229L);
    private static final List<String> POLICY_IDS  = List.of("pol_web1", "pol_web2", "pol_web3");
    private static final List<String> HOSTNAMES   = List.of(
            "www.example.com",
            "api.corp-internal.net",
            "shop.acme-corp.io",
            "portal.enterprise-sys.com",
            "secure.fintech-app.io"
    );

    // ── Paths ─────────────────────────────────────────────────────────────────

    /** Paths that contain "/admin" or "/login" → trigger +15 path bonus. */
    private static final List<String> BONUS_PATHS = List.of(
            "/api/v1/login",
            "/admin/dashboard",
            "/admin/users",
            "/admin/settings",
            "/api/v1/admin/config",
            "/login"
    );

    /** Regular paths — no path bonus. */
    private static final List<String> NORMAL_PATHS = List.of(
            "/api/v1/users",
            "/api/v2/products",
            "/api/v1/orders",
            "/api/v1/search",
            "/static/js/app.js",
            "/api/v1/cart",
            "/api/v2/checkout",
            "/api/v1/reports",
            "/health",
            "/metrics",
            "/api/v1/profile",
            "/api/v2/inventory"
    );

    // ── HTTP methods ──────────────────────────────────────────────────────────

    private static final String[] METHODS         = {"POST", "GET",  "PUT",  "DELETE", "PATCH"};
    private static final int[]    METHOD_WEIGHTS   = {  45,    30,    10,      10,       5};

    // ── Attack categories ─────────────────────────────────────────────────────

    private static final String[] CATEGORIES      = {
            "INJECTION", "BOT", "XSS", "DOS", "RATE_LIMIT", "PROTOCOL_VIOLATION", "DATA_LEAKAGE"
    };
    private static final int[]    CATEGORY_WEIGHTS = {30, 25, 15, 12, 8, 6, 4};

    // ── Severities ────────────────────────────────────────────────────────────

    private static final String[] SEVERITIES      = {"CRITICAL", "HIGH", "MEDIUM", "LOW"};
    private static final int[]    SEVERITY_WEIGHTS = {20, 35, 30, 15};

    // ── Actions ───────────────────────────────────────────────────────────────

    private static final String[] ACTIONS         = {"DENY", "ALERT", "MONITOR"};
    private static final int[]    ACTION_WEIGHTS   = {55, 30, 15};

    // ── Rule catalogue per category ───────────────────────────────────────────

    /**
     * Each entry: { ruleId, ruleName, ruleMessage }.
     * Two rules per category to add variety in the samples API.
     */
    private static final Map<String, List<String[]>> RULES = Map.of(
            "INJECTION",         List.<String[]>of(
                    new String[]{"950001", "SQL_INJECTION",  "SQL Injection Attack Detected"},
                    new String[]{"950002", "CMD_INJECTION",  "Command Injection Attempt Blocked"}
            ),
            "XSS",               List.<String[]>of(
                    new String[]{"941001", "XSS_REFLECTED",  "Reflected XSS Attack Detected"},
                    new String[]{"941002", "XSS_STORED",     "Stored XSS Pattern Detected"}
            ),
            "PROTOCOL_VIOLATION", List.<String[]>of(
                    new String[]{"920001", "MALFORMED_HTTP", "Malformed HTTP Request Detected"},
                    new String[]{"920002", "PROTO_ANOMALY",  "Protocol Anomaly Detected"}
            ),
            "DATA_LEAKAGE",      List.<String[]>of(
                    new String[]{"970001", "PII_EXPOSURE",   "Sensitive Data Exposure Attempt"},
                    new String[]{"970002", "CREDIT_CARD",    "Credit Card Data in Response"}
            ),
            "BOT",               List.<String[]>of(
                    new String[]{"990001", "SCRAPER_BOT",    "Web Scraper Bot Detected"},
                    new String[]{"990002", "CRED_STUFFING",  "Credential Stuffing Bot Detected"}
            ),
            "DOS",               List.<String[]>of(
                    new String[]{"912001", "SLOW_FLOOD",     "Slow-Rate DoS Pattern Detected"},
                    new String[]{"912002", "HTTP_FLOOD",     "HTTP Flood Attack Detected"}
            ),
            "RATE_LIMIT",        List.<String[]>of(
                    new String[]{"901001", "RATE_EXCEEDED",  "Request Rate Limit Exceeded"},
                    new String[]{"901002", "BURST_TRAFFIC",  "Burst Traffic Rate Limit Hit"}
            )
    );

    // ── Geo ───────────────────────────────────────────────────────────────────

    private static final String[] COUNTRIES = {
            "CN", "RU", "BR", "NG", "UA", "IR", "KP", "US", "IN", "VN"
    };

    private static final Map<String, List<String>> CITIES = Map.of(
            "CN", List.of("Beijing", "Shanghai", "Shenzhen"),
            "RU", List.of("Moscow", "Saint Petersburg", "Novosibirsk"),
            "BR", List.of("São Paulo", "Rio de Janeiro", "Brasília"),
            "NG", List.of("Lagos", "Abuja", "Kano"),
            "UA", List.of("Kyiv", "Kharkiv", "Dnipro"),
            "IR", List.of("Tehran", "Isfahan", "Mashhad"),
            "KP", List.of("Pyongyang"),
            "US", List.of("New York", "Los Angeles", "Chicago"),
            "IN", List.of("Mumbai", "New Delhi", "Bengaluru"),
            "VN", List.of("Hanoi", "Ho Chi Minh City", "Da Nang")
    );

    // ── User-agents ───────────────────────────────────────────────────────────

    private static final List<String> USER_AGENTS = List.of(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36",
            "python-requests/2.31.0",
            "curl/8.4.0",
            "sqlmap/1.7.12#stable (https://sqlmap.org)",
            "Go-http-client/1.1",
            "libwww-perl/6.72",
            "Nikto/2.1.6",
            "masscan/1.3.2",
            "Mozilla/5.0 (compatible; SemrushBot/7~bl; +http://www.semrush.com/bot.html)",
            "Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)",
            "ZmEu",
            "WinHttp.WinHttpRequest.5.1",
            "axios/1.6.0",
            "Java/17.0.9",
            "Apache-HttpClient/4.5.14 (Java/11.0.21)",
            "Hydra v9.4",
            "Medusa v2.2",
            "zgrab/0.x",
            "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1)"
    );

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a fully populated, schema-valid event for the given client IP.
     * All other fields are chosen randomly using the weighted distributions above.
     */
    public SecurityEventRequest build(String clientIp) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        String category = weighted(CATEGORIES, CATEGORY_WEIGHTS, rng);
        String severity  = weighted(SEVERITIES, SEVERITY_WEIGHTS, rng);
        String action    = weighted(ACTIONS, ACTION_WEIGHTS, rng);
        String method    = weighted(METHODS, METHOD_WEIGHTS, rng);

        // 35 % of events hit bonus paths (/admin or /login)
        String path = rng.nextInt(100) < 35
                ? BONUS_PATHS.get(rng.nextInt(BONUS_PATHS.size()))
                : NORMAL_PATHS.get(rng.nextInt(NORMAL_PATHS.size()));

        // Timestamp: uniform random over the last 48 hours
        long secondsAgo = rng.nextLong(0, 48L * 3_600);
        Instant timestamp = Instant.now().minusSeconds(secondsAgo);

        // Rule details correlated to category
        String[] ruleData = pickRule(category, rng);

        // Geo
        String country = COUNTRIES[rng.nextInt(COUNTRIES.length)];
        List<String> cities = CITIES.getOrDefault(country, List.of("Unknown"));
        String city = cities.get(rng.nextInt(cities.size()));

        // Configuration — round-robin across the three known config IDs
        int configIdx = rng.nextInt(CONFIG_IDS.size());

        // HTTP status code correlated with action
        int statusCode = statusCode(action, rng);

        // ── Assemble DTO ───────────────────────────────────────────────────

        RuleDto rule = new RuleDto();
        rule.setId(ruleData[0]);
        rule.setName(ruleData[1]);
        rule.setMessage(ruleData[2]);
        rule.setSeverity(severity);
        rule.setCategory(category);

        GeoLocationDto geo = new GeoLocationDto();
        geo.setCountry(country);
        geo.setCity(city);

        SecurityEventRequest req = new SecurityEventRequest();
        req.setEventId(UUID.randomUUID().toString());
        req.setEventTimestamp(timestamp);
        req.setConfigId(CONFIG_IDS.get(configIdx));
        req.setPolicyId(POLICY_IDS.get(configIdx));
        req.setClientIp(clientIp);
        req.setHostname(HOSTNAMES.get(configIdx % HOSTNAMES.size()));
        req.setPath(path);
        req.setMethod(method);
        req.setStatusCode(statusCode);
        req.setUserAgent(USER_AGENTS.get(rng.nextInt(USER_AGENTS.size())));
        req.setRule(rule);
        req.setAction(action);
        req.setGeoLocation(geo);
        req.setRequestSize(rng.nextInt(128, 8_192));
        req.setResponseSize(rng.nextInt(64, 2_048));

        return req;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String weighted(String[] items, int[] weights, ThreadLocalRandom rng) {
        int total = 0;
        for (int w : weights) total += w;
        int rand = rng.nextInt(total);
        int cumulative = 0;
        for (int i = 0; i < items.length; i++) {
            cumulative += weights[i];
            if (rand < cumulative) return items[i];
        }
        return items[items.length - 1];
    }

    private static String[] pickRule(String category, ThreadLocalRandom rng) {
        List<String[]> candidates = RULES.getOrDefault(category,
                List.<String[]>of(new String[]{"000000", "UNKNOWN", "Unknown attack pattern"}));
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private static int statusCode(String action, ThreadLocalRandom rng) {
        return switch (action) {
            case "DENY"    -> new int[]{400, 403, 429}[rng.nextInt(3)];
            case "ALERT"   -> rng.nextInt(10) < 7 ? 200 : 403;
            case "MONITOR" -> 200;
            default        -> 200;
        };
    }
}
