package nmk_backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ApiRateLimitService {
    private static final String UPSERT_SQL = """
            INSERT INTO api_rate_limit_buckets
                (bucket_key, window_start, request_count, expires_at)
            VALUES (?, ?, 1, ?)
            ON DUPLICATE KEY UPDATE request_count = request_count + 1
            """;
    private static final String COUNT_SQL =
            "SELECT request_count FROM api_rate_limit_buckets WHERE bucket_key = ?";

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;
    private final AtomicLong cleanupCounter = new AtomicLong();

    public ApiRateLimitService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.rate-limit.enabled:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    public RateLimitDecision increment(String route, String clientAddress, int limit, int windowSeconds) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }

        long nowEpoch = Instant.now().getEpochSecond();
        long windowNumber = Math.floorDiv(nowEpoch, windowSeconds);
        Instant windowStart = Instant.ofEpochSecond(windowNumber * windowSeconds);
        Instant windowEnd = windowStart.plusSeconds(windowSeconds);
        Instant expiresAt = windowEnd.plusSeconds(86_400);

        String key = sha256(route + "|" + safeClient(clientAddress) + "|" + windowNumber);
        Timestamp startTimestamp = Timestamp.valueOf(LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC));
        Timestamp expiryTimestamp = Timestamp.valueOf(LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));

        jdbcTemplate.update(UPSERT_SQL, key, startTimestamp, expiryTimestamp);
        if (cleanupCounter.incrementAndGet() % 1000 == 0) {
            jdbcTemplate.update(
                    "DELETE FROM api_rate_limit_buckets WHERE expires_at < ? LIMIT 1000",
                    Timestamp.from(Instant.now())
            );
        }
        Integer count = jdbcTemplate.queryForObject(COUNT_SQL, Integer.class, key);
        int currentCount = count == null ? 0 : count;
        int retryAfter = (int) Math.max(1, windowEnd.getEpochSecond() - nowEpoch);

        return new RateLimitDecision(currentCount <= limit, retryAfter, currentCount, limit);
    }

    public boolean isEnabled() {
        return enabled;
    }

    private static String safeClient(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public record RateLimitDecision(boolean allowed, int retryAfterSeconds, int currentCount, int limit) {}
}
