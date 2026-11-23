package nmk_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "app.rate-limit.enabled=true")
@ActiveProfiles("mysql-test")
@Sql(scripts = "/db/manual/018-create-api-rate-limit-buckets.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ApiRateLimitServiceMySqlTest {
    @Autowired private ApiRateLimitService service;

    @Test
    void concurrentRequestsShareOneAtomicWindowCounter() throws Exception {
        String route = "concurrent-test-" + UUID.randomUUID();
        String client = "test-peer-" + UUID.randomUUID();
        int requests = 12;
        int limit = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ApiRateLimitService.RateLimitDecision>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Rate-limit test start timed out");
                    }
                    return service.increment(route, client, limit, 3600);
                }));
            }

            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        var finalDecision = service.increment(route, client, limit, 3600);
        assertFalse(finalDecision.allowed());
        assertEquals(requests + 1, finalDecision.currentCount());
        assertEquals(limit, finalDecision.limit());
    }
}
