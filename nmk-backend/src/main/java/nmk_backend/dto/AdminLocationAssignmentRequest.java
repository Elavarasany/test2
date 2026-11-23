package nmk_backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AdminLocationAssignmentRequest(
        @NotNull @Size(max = 100) List<@NotNull Long> locationIds
) {}