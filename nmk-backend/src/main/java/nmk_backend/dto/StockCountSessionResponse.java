package nmk_backend.dto;

import nmk_backend.entity.StockCountSession;

import java.time.LocalDateTime;
import java.util.List;

public record StockCountSessionResponse(
        Long id,
        Long locationId,
        String locationName,
        String status,
        String reason,
        String startedByUsername,
        String submittedByUsername,
        String postedByUsername,
        String cancelledByUsername,
        LocalDateTime startedAt,
        LocalDateTime submittedAt,
        LocalDateTime postedAt,
        LocalDateTime cancelledAt,
        Integer lineCount,
        Integer countedLineCount,
        Integer varianceLineCount,
        List<StockCountLineResponse> lines
) {
    public static StockCountSessionResponse from(StockCountSession session) {
        List<StockCountLineResponse> lines = session.getLines().stream()
                .map(StockCountLineResponse::from).toList();
        return new StockCountSessionResponse(
                session.getId(),
                session.getLocation().getId(),
                session.getLocation().getName(),
                session.getStatus(),
                session.getReason(),
                session.getStartedByUsername(),
                session.getSubmittedByUsername(),
                session.getPostedByUsername(),
                session.getCancelledByUsername(),
                session.getStartedAt(),
                session.getSubmittedAt(),
                session.getPostedAt(),
                session.getCancelledAt(),
                lines.size(),
                (int) lines.stream().filter(line -> line.countedQuantity() != null).count(),
                (int) lines.stream().filter(line -> line.variance() != null && line.variance() != 0).count(),
                lines
        );
    }
}