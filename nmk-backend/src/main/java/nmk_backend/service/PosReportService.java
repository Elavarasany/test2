package nmk_backend.service;

import nmk_backend.dto.PosSalesReportResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import nmk_backend.dto.PosInventoryReportResponse;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.InventoryBalanceRepository;
import nmk_backend.repository.PosReturnRepository;
import nmk_backend.repository.PosSaleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class PosReportService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int REPORT_BATCH_SIZE = 200;
    private static final int INVENTORY_FIRST_PAGE_SIZE = 50;

    private final PosSaleRepository saleRepository;
    private final PosReturnRepository returnRepository;
    private final InventoryLocationRepository locationRepository;
    private final nmk_backend.repository.PosExchangeCreditSettlementRepository exchangeCreditRepository;
    private final InventoryBalanceRepository inventoryBalanceRepository;
    private final AdminLocationAccessService locationAccessService;

    @PersistenceContext
    private EntityManager entityManager;

    public PosReportService(
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            InventoryLocationRepository locationRepository,
            nmk_backend.repository.PosExchangeCreditSettlementRepository exchangeCreditRepository,
            InventoryBalanceRepository inventoryBalanceRepository
    ) {
        this(saleRepository, returnRepository, locationRepository, exchangeCreditRepository, inventoryBalanceRepository, null);
    }

    @Autowired
    public PosReportService(
            PosSaleRepository saleRepository,
            PosReturnRepository returnRepository,
            InventoryLocationRepository locationRepository,
            nmk_backend.repository.PosExchangeCreditSettlementRepository exchangeCreditRepository,
            InventoryBalanceRepository inventoryBalanceRepository,
            AdminLocationAccessService locationAccessService
    ) {
        this.saleRepository = saleRepository;
        this.returnRepository = returnRepository;
        this.locationRepository = locationRepository;
        this.exchangeCreditRepository = exchangeCreditRepository;
        this.inventoryBalanceRepository = inventoryBalanceRepository;
        this.locationAccessService = locationAccessService;
    }

    @Transactional(readOnly = true)
    public PosInventoryReportResponse getInventoryReport(Long locationId) {
        return getInventoryReport(locationId, null, null);
    }

    @Transactional(readOnly = true)
    public PosInventoryReportResponse getInventoryReport(Long locationId, String username, String role) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid inventory location is required");
        }
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, locationId);
        InventoryLocation location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        Long reportLocationId = location.getId();
        String locationCode = location.getCode();
        String locationName = location.getName();
        List<PosInventoryReportResponse.VariantStock> firstPageRows = new ArrayList<>(INVENTORY_FIRST_PAGE_SIZE);
        long variantCount = 0;
        long onHandQuantity = 0;
        long reservedQuantity = 0;
        long availableQuantity = 0;
        int lowStockCount = 0;
        int pageNumber = 0;
        Page<InventoryBalance> page;
        do {
            page = inventoryBalanceRepository.findByLocationId(
                    locationId,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "id"))
            );
            for (InventoryBalance balance : page.getContent()) {
                var row = PosInventoryReportResponse.VariantStock.from(balance);
                variantCount++;
                onHandQuantity += row.onHandQuantity();
                reservedQuantity += row.reservedQuantity();
                availableQuantity += row.availableQuantity();
                if (row.lowStock()) lowStockCount++;
                if (firstPageRows.size() < INVENTORY_FIRST_PAGE_SIZE) firstPageRows.add(row);
            }
            // Keep only the first display page in memory while accumulating scalar totals.
            entityManager.clear();
        } while (page.hasNext());

        return PosInventoryReportResponse.fromAggregates(
                reportLocationId, locationCode, locationName, variantCount,
                onHandQuantity, reservedQuantity, availableQuantity, lowStockCount,
                0, INVENTORY_FIRST_PAGE_SIZE, variantCount > INVENTORY_FIRST_PAGE_SIZE, firstPageRows
        );
    }

    @Transactional(readOnly = true)
    public Page<PosInventoryReportResponse.VariantStock> getInventoryReportEntries(
            Long locationId, int page, int size, String username, String role
    ) {
        if (locationId == null || locationId <= 0) {
            throw new IllegalArgumentException("A valid inventory location is required");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
        }
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, locationId);
        locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));

        return inventoryBalanceRepository.findByLocationId(
                        locationId,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))
                )
                .map(PosInventoryReportResponse.VariantStock::from);
    }

    @Transactional(readOnly = true)
    public PosSalesReportResponse getSalesReport(Long locationId, LocalDate from, LocalDate to) {
        return getSalesReport(locationId, from, to, null, null);
    }

    @Transactional(readOnly = true)
    public PosSalesReportResponse getSalesReport(Long locationId, LocalDate from, LocalDate to, String username, String role) {
        if (locationId == null || locationId <= 0 || from == null || to == null) {
            throw new IllegalArgumentException("Location, start date and end date are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("End date cannot be before start date");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("Report date range cannot exceed 367 calendar days");
        }
        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, locationId);
        locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));

        var fromDateTime = from.atStartOfDay();
        var toExclusive = to.plusDays(1).atStartOfDay();

        BigDecimal exchangeCreditApplied = Optional.ofNullable(
                exchangeCreditRepository.sumExchangeCreditApplied(locationId, fromDateTime, toExclusive)
        ).orElse(ZERO);

        ReportAccumulator totals = new ReportAccumulator();
        int pageNumber = 0;
        Page<PosSale> salePage;
        do {
            salePage = saleRepository.findCompletedSalesForReport(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt", "id"))
            );
            for (PosSale sale : salePage.getContent()) {
                totals.addSale(sale);
            }
            // All sale values and lazy child collections have been consumed by the
            // accumulator; detach this batch before loading the next page.
            entityManager.clear();
        } while (salePage.hasNext());

        pageNumber = 0;
        Page<PosReturn> returnPage;
        do {
            returnPage = returnRepository.findCompletedReturnsForReport(
                    locationId, fromDateTime, toExclusive,
                    PageRequest.of(pageNumber++, REPORT_BATCH_SIZE, Sort.by(Sort.Direction.ASC, "createdAt", "id"))
            );
            for (PosReturn record : returnPage.getContent()) {
                totals.addReturn(record);
            }
            // Return details and refund allocations are consumed before detaching.
            entityManager.clear();
        } while (returnPage.hasNext());

        return totals.toResponse(locationId, from, to, exchangeCreditApplied);
    }

    private static class ReportAccumulator {
        private int transactionCount;
        private BigDecimal subtotal = ZERO;
        private BigDecimal discounts = ZERO;
        private BigDecimal netSales = ZERO;
        private BigDecimal refundTotal = ZERO;
        private BigDecimal salesReversals = ZERO;
        private BigDecimal recordedRefundPaymentTotal = ZERO;
        private int customerReturnCount;
        private final Map<String, BigDecimal> salesByPayment = new TreeMap<>();
        private final Map<String, BigDecimal> refundsByPayment = new TreeMap<>();
        private final Map<String, MutableCashier> cashiers = new TreeMap<>();
        private final Map<LocalDate, MutableDaily> daily = new TreeMap<>();
        private final Map<Long, MutableProduct> products = new HashMap<>();

        private void addSale(PosSale sale) {
            transactionCount++;
            subtotal = subtotal.add(sale.getSubtotal());
            discounts = discounts.add(sale.getDiscountAmount());
            netSales = netSales.add(sale.getGrandTotal());

            MutableCashier cashier = cashiers.computeIfAbsent(
                    sale.getCashierUsername(), ignored -> new MutableCashier()
            );
            cashier.transactionCount++;
            cashier.subtotal = cashier.subtotal.add(sale.getSubtotal());
            cashier.discounts = cashier.discounts.add(sale.getDiscountAmount());
            cashier.netSales = cashier.netSales.add(sale.getGrandTotal());

            MutableDaily day = daily.computeIfAbsent(
                    sale.getCreatedAt().toLocalDate(), ignored -> new MutableDaily()
            );
            day.transactionCount++;
            day.netSales = day.netSales.add(sale.getGrandTotal());

            for (PosPayment payment : sale.getPayments()) {
                salesByPayment.merge(payment.getPaymentMethod(), payment.getAmount(), BigDecimal::add);
            }
            for (PosSaleItem item : sale.getItems()) {
                MutableProduct product = products.computeIfAbsent(
                        item.getProductId(),
                        ignored -> new MutableProduct(item.getProductId(), item.getProductName())
                );
                product.quantitySold += item.getQuantity();
                product.grossLineTotal = product.grossLineTotal.add(item.getLineTotal());
            }
        }

        private void addReturn(PosReturn record) {
            refundTotal = refundTotal.add(record.getRefundTotal());
            if ("VOID".equals(record.getReturnType())) {
                salesReversals = salesReversals.add(record.getRefundTotal());
            }
            MutableDaily day = daily.computeIfAbsent(
                    record.getCreatedAt().toLocalDate(), ignored -> new MutableDaily()
            );
            // A full void is a reversal, not a customer return.
            if ("RETURN".equals(record.getReturnType())) {
                customerReturnCount++;
                day.returnCount++;
            }
            day.refunds = day.refunds.add(record.getRefundTotal());

            for (PosRefundPayment payment : record.getRefundPayments()) {
                refundsByPayment.merge(payment.getPaymentMethod(), payment.getAmount(), BigDecimal::add);
                recordedRefundPaymentTotal = recordedRefundPaymentTotal.add(payment.getAmount());
            }
        }

        private PosSalesReportResponse toResponse(
                Long locationId, LocalDate from, LocalDate to, BigDecimal exchangeCreditApplied
        ) {
            List<PosSalesReportResponse.CashierTotals> cashierRows = cashiers.entrySet().stream()
                    .map(entry -> new PosSalesReportResponse.CashierTotals(
                            entry.getKey(), entry.getValue().transactionCount,
                            entry.getValue().subtotal, entry.getValue().discounts, entry.getValue().netSales
                    ))
                    .sorted(Comparator.comparing(
                            PosSalesReportResponse.CashierTotals::netSales, Comparator.reverseOrder()
                    ))
                    .toList();

            List<PosSalesReportResponse.DailyTotals> dailyRows = daily.entrySet().stream()
                    .map(entry -> new PosSalesReportResponse.DailyTotals(
                            entry.getKey(), entry.getValue().transactionCount, entry.getValue().netSales,
                            entry.getValue().returnCount, entry.getValue().refunds,
                            entry.getValue().netSales.subtract(entry.getValue().refunds)
                    ))
                    .toList();

            List<PosSalesReportResponse.ProductTotals> topProducts = products.values().stream()
                    .map(product -> new PosSalesReportResponse.ProductTotals(
                            product.productId, product.productName, product.quantitySold, product.grossLineTotal
                    ))
                    .sorted(Comparator.comparing(
                            PosSalesReportResponse.ProductTotals::grossLineTotal, Comparator.reverseOrder()
                    ))
                    .limit(10)
                    .toList();

            return new PosSalesReportResponse(
                    locationId, from, to, transactionCount,
                    subtotal, discounts, netSales,
                    customerReturnCount, refundTotal, salesReversals,
                    recordedRefundPaymentTotal, exchangeCreditApplied, netSales.subtract(refundTotal),
                    Collections.unmodifiableMap(salesByPayment),
                    Collections.unmodifiableMap(refundsByPayment),
                    cashierRows, dailyRows, topProducts
            );
        }
    }

    private static class MutableCashier {
        private int transactionCount;
        private BigDecimal subtotal = ZERO;
        private BigDecimal discounts = ZERO;
        private BigDecimal netSales = ZERO;
    }

    private static class MutableDaily {
        private int transactionCount;
        private BigDecimal netSales = ZERO;
        private int returnCount;
        private BigDecimal refunds = ZERO;
    }

    private static class MutableProduct {
        private final Long productId;
        private final String productName;
        private long quantitySold;
        private BigDecimal grossLineTotal = ZERO;

        private MutableProduct(Long productId, String productName) {
            this.productId = productId;
            this.productName = productName;
        }
    }
}