package nmk_backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierPayablesAgingResponse(
        Long supplierId,
        LocalDate asOfDate,
        int openBillCount,
        int overdueBillCount,
        BigDecimal notDue,
        BigDecimal overdue1To30Days,
        BigDecimal overdue31To60Days,
        BigDecimal overdue61To90Days,
        BigDecimal overdueOver90Days,
        BigDecimal totalOutstanding
) {}
