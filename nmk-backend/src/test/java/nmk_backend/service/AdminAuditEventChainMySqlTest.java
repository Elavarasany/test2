package nmk_backend.service;

import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.repository.AdminAuditEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.jdbc.Sql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
@Sql(scripts = "/db/manual/016-create-tamper-evident-admin-audit-chain.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class AdminAuditEventChainMySqlTest {
    @Autowired private AdminAuditEventService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentAuditWritesRemainInOneVerifiableChainOnMySql() throws Exception {
        String runId = UUID.randomUUID().toString();
        service.record(event(runId + "-seed"));

        int workers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Audit writer start signal timed out");
                    }
                    service.record(event(runId + "-" + index));
                    return null;
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS), "All audit writers should be ready");
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        var integrity = service.verifyIntegrity();
        assertTrue(integrity.valid(), integrity.failureReason());
        assertTrue(integrity.checkedEvents() >= workers + 1);
        assertNotNull(integrity.lastEventId());
        assertEquals(integrity.calculatedLastHash(), integrity.storedLastHash());
    }

    @Test
    void databaseRejectsAuditEventUpdatesAndDeletes() {
        String requestId = UUID.randomUUID().toString();
        service.record(event(requestId));
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM admin_audit_events WHERE request_id = ?",
                Long.class,
                requestId
        );

        assertNotNull(eventId);
        assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE admin_audit_events SET outcome = 'FAILURE' WHERE id = ?", eventId));
        assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("DELETE FROM admin_audit_events WHERE id = ?", eventId));
        assertTrue(service.verifyIntegrity().valid());
    }

    private static AdminAuditEvent event(String requestId) {
        AdminAuditEvent event = new AdminAuditEvent();
        event.setActorUsername("audit-chain-test");
        event.setActorAuthorities("ROLE_ADMIN");
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/audit-chain-test");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");
        event.setRequestId(requestId);
        return event;
    }
}
