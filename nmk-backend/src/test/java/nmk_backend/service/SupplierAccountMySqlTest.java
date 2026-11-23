package nmk_backend.service;

import nmk_backend.dto.SupplierPaymentRequest;
import nmk_backend.dto.SupplierCreditNoteRequest;
import nmk_backend.entity.Admin;
import nmk_backend.entity.Supplier;
import nmk_backend.entity.SupplierBill;
import nmk_backend.repository.AdminRepository;
import nmk_backend.repository.SupplierBillRepository;
import nmk_backend.repository.SupplierPaymentRepository;
import nmk_backend.repository.SupplierBillCreditNoteRepository;
import nmk_backend.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("mysql-test")
class SupplierAccountMySqlTest {
    @Autowired private SupplierAccountService supplierAccountService;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private SupplierBillRepository billRepository;
    @Autowired private SupplierPaymentRepository paymentRepository;
    @Autowired private SupplierBillCreditNoteRepository creditNoteRepository;
    @Autowired private AdminRepository adminRepository;

    @Test
    void concurrentSupplierPaymentsCannotOverpayOneBill() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Admin admin = createAdmin(suffix);
        Supplier supplier = createSupplier(suffix);
        SupplierBill bill = createBill(supplier, suffix, new BigDecimal("100.00"));
        Long billId = bill.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() ->
                    attemptPayment(billId, suffix, "one", admin.getUsername(), ready, start));
            Future<Boolean> second = executor.submit(() ->
                    attemptPayment(billId, suffix, "two", admin.getUsername(), ready, start));
            ready.await();
            start.countDown();

            int accepted = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, accepted);
            assertEquals(new BigDecimal("60.00"), paymentRepository.sumConfirmedAmountByBillId(billId));
            assertEquals(1, paymentRepository.findByBillIdOrderByPaidAtDescIdDesc(billId).size());
        }
    }

    @Test
    void supplierPaymentIdempotencyDoesNotCreateDuplicateLedgerRows() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Admin admin = createAdmin(suffix);
        Supplier supplier = createSupplier(suffix);
        SupplierBill bill = createBill(supplier, suffix, new BigDecimal("100.00"));
        String key = "supplierpay_" + suffix;

        SupplierPaymentRequest request = new SupplierPaymentRequest(
                new BigDecimal("25.00"), "BANK", "UTR-" + suffix, null, key
        );
        var first = supplierAccountService.recordPayment(bill.getId(), request, admin.getUsername(), "ADMIN");
        var retry = supplierAccountService.recordPayment(bill.getId(), request, admin.getUsername(), "ADMIN");

        assertEquals(first.id(), retry.id());
        assertEquals(1, paymentRepository.findByBillIdOrderByPaidAtDescIdDesc(bill.getId()).size());
        assertEquals(new BigDecimal("25.00"), paymentRepository.sumConfirmedAmountByBillId(bill.getId()));
    }

    @Test
    void concurrentPaymentAndCreditNoteCannotReduceOneBillBelowZero() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Admin admin = createAdmin(suffix);
        Supplier supplier = createSupplier(suffix);
        SupplierBill bill = createBill(supplier, suffix, new BigDecimal("100.00"));
        Long billId = bill.getId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> payment = executor.submit(() -> attemptPayment(
                    billId, suffix, "cross", admin.getUsername(), ready, start));
            Future<Boolean> credit = executor.submit(() -> attemptCreditNote(
                    billId, suffix, admin.getUsername(), ready, start));
            ready.await();
            start.countDown();

            int accepted = (payment.get() ? 1 : 0) + (credit.get() ? 1 : 0);
            assertEquals(1, accepted);
            SupplierBill refreshed = billRepository.findById(billId).orElseThrow();
            assertEquals(new BigDecimal("60.00"),
                    refreshed.getPaidAmount().add(refreshed.getCreditedAmount()));
        }
    }

    private boolean attemptCreditNote(Long billId, String suffix, String username,
                                      CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try {
            supplierAccountService.recordCreditNote(
                    billId,
                    new SupplierCreditNoteRequest(
                            "CN-" + suffix, LocalDate.now(), new BigDecimal("60.00"),
                            "Concurrent supplier return credit", null, "suppliercredit_" + suffix
                    ),
                    username
            );
            return true;
        } catch (IllegalArgumentException outstandingBalance) {
            return false;
        }
    }

    private boolean attemptPayment(Long billId, String suffix, String label, String username,
                                   CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                supplierAccountService.recordPayment(
                        billId,
                        new SupplierPaymentRequest(
                                new BigDecimal("60.00"), "BANK", "UTR-" + suffix + "-" + label,
                                null, "supplierpay_" + suffix + "_" + label
                        ),
                        username,
                        "ADMIN"
                );
                return true;
            } catch (org.springframework.dao.PessimisticLockingFailureException lockFailure) {
                if (attempt == 2) throw lockFailure;
                Thread.sleep(40L * (attempt + 1));
            } catch (IllegalArgumentException outstandingBalance) {
                return false;
            }
        }
        return false;
    }

    private Admin createAdmin(String suffix) {
        Admin admin = new Admin();
        admin.setUsername("supplier_admin_" + suffix.substring(0, 12));
        admin.setPassword("unused-test-password");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        return adminRepository.saveAndFlush(admin);
    }

    private Supplier createSupplier(String suffix) {
        Supplier supplier = new Supplier();
        supplier.setName("Supplier AP Test " + suffix.substring(0, 12));
        supplier.setActive(true);
        return supplierRepository.saveAndFlush(supplier);
    }

    private SupplierBill createBill(Supplier supplier, String suffix, BigDecimal amount) {
        SupplierBill bill = new SupplierBill();
        bill.setSupplier(supplier);
        bill.setInvoiceNumber("AP-" + suffix);
        bill.setInvoiceDate(LocalDate.now());
        bill.setAmount(amount);
        bill.setBillType("OTHER");
        bill.setStatus("POSTED");
        bill.setCreatedByUsername("test");
        bill.setIdempotencyKey("supplierbill_" + suffix);
        bill.setRequestFingerprint("a".repeat(64));
        return billRepository.saveAndFlush(bill);
    }
}
