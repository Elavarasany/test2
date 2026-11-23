package nmk_backend.dto;

import nmk_backend.entity.PosCashMovement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PosCashMovementResponse(
        Long id,
        Long shiftId,
        String movementType,
        String direction,
        BigDecimal amount,
        String referenceType,
        Long referenceId,
        String actorUsername,
        String reason,
        LocalDateTime createdAt
) {
    public static PosCashMovementResponse from(PosCashMovement movement) {
        return new PosCashMovementResponse(
                movement.getId(),
                movement.getShift().getId(),
                movement.getMovementType(),
                movement.getDirection(),
                movement.getAmount(),
                movement.getReferenceType(),
                movement.getReferenceId(),
                movement.getActorUsername(),
                movement.getReason(),
                movement.getCreatedAt()
        );
    }
}