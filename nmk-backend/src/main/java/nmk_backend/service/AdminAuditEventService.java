package nmk_backend.service;

import nmk_backend.dto.AdminAuditEventResponse;
import nmk_backend.entity.AdminAuditChainState;
import nmk_backend.entity.AdminAuditEvent;
import nmk_backend.repository.AdminAuditChainStateRepository;
import nmk_backend.repository.AdminAuditEventRepository;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class AdminAuditEventService {
    public static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final int VERIFY_BATCH_SIZE = 500;
    private final AdminAuditEventRepository repository;
    private final AdminAuditChainStateRepository chainStateRepository;

    public AdminAuditEventService(AdminAuditEventRepository repository,
                                  AdminAuditChainStateRepository chainStateRepository) {
        this.repository = repository;
        this.chainStateRepository = chainStateRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminAuditEvent event) {
        AdminAuditChainState state = chainStateRepository.findByIdForUpdate(1)
                .orElseGet(this::createInitialState);
        String previousHash = state.getLastHash() == null ? GENESIS_HASH : state.getLastHash();
        event.setPreviousHash(previousHash);
        event.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        event.setEventHash(calculateHash(event));

        AdminAuditEvent saved = repository.saveAndFlush(event);
        state.setLastEventId(saved.getId());
        state.setLastHash(saved.getEventHash());
        chainStateRepository.saveAndFlush(state);
    }

    @Transactional(readOnly = true)
    public IntegrityCheckResponse verifyIntegrity() {
        AdminAuditChainState state = chainStateRepository.findById(1).orElse(null);
        if (state == null) {
            return new IntegrityCheckResponse(false, 0, null, null, null,
                    "Audit chain state is missing", LocalDateTime.now());
        }

        String expectedPreviousHash = GENESIS_HASH;
        Long lastEventId = null;
        long checked = 0;
        int pageNumber = 0;
        Page<AdminAuditEvent> page;
        do {
            page = repository.findAll(PageRequest.of(pageNumber++, VERIFY_BATCH_SIZE,
                    Sort.by(Sort.Direction.ASC, "id")));
            for (AdminAuditEvent event : page.getContent()) {
                checked++;
                if (!Objects.equals(expectedPreviousHash, event.getPreviousHash())) {
                    return new IntegrityCheckResponse(false, checked, event.getId(),
                            expectedPreviousHash, event.getPreviousHash(),
                            "Previous-hash link does not match at event " + event.getId(),
                            LocalDateTime.now());
                }
                String calculated = calculateHash(event);
                if (!calculated.equals(event.getEventHash())) {
                    return new IntegrityCheckResponse(false, checked, event.getId(),
                            calculated, event.getEventHash(),
                            "Event hash does not match at event " + event.getId(),
                            LocalDateTime.now());
                }
                expectedPreviousHash = event.getEventHash();
                lastEventId = event.getId();
            }
        } while (page.hasNext());

        if (!Objects.equals(lastEventId, state.getLastEventId())
                || !Objects.equals(expectedPreviousHash, state.getLastHash())) {
            return new IntegrityCheckResponse(false, checked, lastEventId,
                    expectedPreviousHash, state.getLastHash(),
                    "Audit chain state does not match the final event",
                    LocalDateTime.now());
        }
        return new IntegrityCheckResponse(true, checked, lastEventId,
                expectedPreviousHash, state.getLastHash(), null, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditEventResponse> getEvents(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        return repository.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .map(AdminAuditEventResponse::from);
    }

    private AdminAuditChainState createInitialState() {
        AdminAuditChainState state = new AdminAuditChainState();
        state.setId(1);
        state.setLastHash(GENESIS_HASH);
        return chainStateRepository.saveAndFlush(state);
    }

    private static String calculateHash(AdminAuditEvent event) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, event.getPreviousHash());
            update(digest, event.getActorUsername());
            update(digest, event.getActorAuthorities());
            update(digest, event.getHttpMethod());
            update(digest, event.getRequestPath());
            update(digest, Integer.toString(event.getResponseStatus()));
            update(digest, event.getOutcome());
            update(digest, event.getRemoteAddress());
            update(digest, event.getUserAgent());
            update(digest, event.getRequestId());
            update(digest, event.getErrorType());
            update(digest, event.getCreatedAt() == null ? null : event.getCreatedAt().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) digest.update(bytes);
    }

    public record IntegrityCheckResponse(
            boolean valid,
            long checkedEvents,
            Long lastEventId,
            String calculatedLastHash,
            String storedLastHash,
            String failureReason,
            LocalDateTime checkedAt
    ) {}
}
