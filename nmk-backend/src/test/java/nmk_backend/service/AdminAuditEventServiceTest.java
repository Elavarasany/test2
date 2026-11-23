package nmk_backend.service;

import nmk_backend.dto.AdminAuditEventResponse;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.entity.AdminAuditChainState;
import nmk_backend.repository.AdminAuditChainStateRepository;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import nmk_backend.repository.AdminAuditEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminAuditEventServiceTest {
    @Mock
    private AdminAuditEventRepository repository;

    @Mock
    private AdminAuditChainStateRepository chainStateRepository;

    @InjectMocks
    private AdminAuditEventService service;

    @Test
    void recordPersistsEvent() {
        AdminAuditEvent event = new AdminAuditEvent();
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/pos/checkout");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");

        AdminAuditChainState state = new AdminAuditChainState();
        state.setId(1);
        state.setLastHash(AdminAuditEventService.GENESIS_HASH);
        when(chainStateRepository.findByIdForUpdate(1)).thenReturn(Optional.of(state));
        when(repository.saveAndFlush(event)).thenAnswer(invocation -> {
            ReflectionTestUtils.setField(event, "id", 42L);
            return event;
        });

        service.record(event);

        assertEquals(AdminAuditEventService.GENESIS_HASH, event.getPreviousHash());
        assertNotNull(event.getEventHash());
        assertEquals(64, event.getEventHash().length());
        assertEquals(42L, state.getLastEventId());
        assertEquals(event.getEventHash(), state.getLastHash());
        verify(repository).saveAndFlush(event);
        verify(chainStateRepository).saveAndFlush(state);
    }

    @Test
    void getEventsReturnsPagedSafeResponses() {
        AdminAuditEvent event = new AdminAuditEvent();
        event.setActorUsername("cashier");
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/pos/checkout");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(event)));

        var result = service.getEvents(0, 25);

        assertEquals(1, result.getTotalElements());
        AdminAuditEventResponse response = result.getContent().get(0);
        assertEquals("cashier", response.actorUsername());
        assertEquals("POST", response.httpMethod());
        assertEquals("/api/admin/pos/checkout", response.requestPath());
        assertEquals("SUCCESS", response.outcome());
    }

    @Test
    void verifiesAValidHashChain() {
        AdminAuditChainState state = new AdminAuditChainState();
        state.setId(1);
        state.setLastHash(AdminAuditEventService.GENESIS_HASH);
        when(chainStateRepository.findByIdForUpdate(1)).thenReturn(Optional.of(state));

        AdminAuditEvent event = new AdminAuditEvent();
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/staff");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");
        ReflectionTestUtils.setField(event, "id", 7L);
        when(repository.saveAndFlush(event)).thenReturn(event);
        service.record(event);
        when(chainStateRepository.findById(1)).thenReturn(Optional.of(state));
        when(repository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(event)));

        var result = service.verifyIntegrity();

        assertTrue(result.valid());
        assertEquals(1, result.checkedEvents());
        assertEquals(7L, result.lastEventId());
    }

    @Test
    void detectsTamperedAuditEvent() {
        AdminAuditChainState state = new AdminAuditChainState();
        state.setId(1);
        state.setLastHash(AdminAuditEventService.GENESIS_HASH);
        when(chainStateRepository.findByIdForUpdate(1)).thenReturn(Optional.of(state));

        AdminAuditEvent event = new AdminAuditEvent();
        event.setHttpMethod("POST");
        event.setRequestPath("/api/admin/staff");
        event.setResponseStatus(201);
        event.setOutcome("SUCCESS");
        ReflectionTestUtils.setField(event, "id", 8L);
        when(repository.saveAndFlush(event)).thenReturn(event);
        service.record(event);
        event.setRequestPath("/api/admin/changed");
        when(chainStateRepository.findById(1)).thenReturn(Optional.of(state));
        when(repository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(event)));

        var result = service.verifyIntegrity();

        assertFalse(result.valid());
        assertEquals(8L, result.lastEventId());
        assertTrue(result.failureReason().contains("Event hash does not match"));
    }

    @Test
    void rejectsInvalidPageAndSize() {
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(-1, 25));
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(0, 0));
        assertThrows(IllegalArgumentException.class, () -> service.getEvents(0, 101));
        verifyNoInteractions(repository);
    }
}