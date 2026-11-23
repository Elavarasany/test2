
package nmk_backend.security;
import nmk_backend.entity.Admin;
import nmk_backend.repository.AdminRepository;
import nmk_backend.security.JwtService;

import nmk_backend.repository.AppointmentRepository;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class SecurityAuthorizationIntegrationTest {
    @Autowired
private AdminRepository adminRepository;

@Autowired
private JwtService jwtService;

@Autowired
private PasswordEncoder passwordEncoder;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }
    @Test
void validAdminTokenCanAccessAdminProductList() throws Exception {

    String username = "security-test-admin";

    adminRepository.findByUsername(username)
            .ifPresent(adminRepository::delete);

    Admin admin = new Admin();
    admin.setUsername(username);
    admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

    adminRepository.saveAndFlush(admin);

    try {
        String token = jwtService.generateToken(username);

        mockMvc.perform(get("/api/admin/products")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

    } finally {
        adminRepository.findByUsername(username)
                .ifPresent(adminRepository::delete);
    }
}

    @Test
    void publicProductListReportsUnavailableWhenCheckoutLocationIsNotConfigured() throws Exception {
        // ResponseStatusException intentionally uses Spring's default error
        // response, which does not expose exception messages in production.
        // Assert the service contract without depending on an error-body detail.
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isServiceUnavailable());
    }

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Test
    void clearingAppointmentsWithoutActionAuthorizationReturnsForbidden()
            throws Exception {

        String username = "appointment-security-test-admin";

        adminRepository.findByUsername(username)
                .ifPresent(adminRepository::delete);

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

        adminRepository.saveAndFlush(admin);

        try {
            String token = jwtService.generateToken(username);

            long appointmentsBefore = appointmentRepository.count();

            mockMvc.perform(delete("/api/appointments/clear")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("Fresh password authorization is required"));

            assertEquals(appointmentsBefore, appointmentRepository.count());

        } finally {
            adminRepository.findByUsername(username)
                    .ifPresent(adminRepository::delete);
        }
    }

    @Test
    void clearingAppointmentsWithInvalidActionAuthorizationReturnsForbidden()
            throws Exception {

        String username = "appointment-invalid-token-test-admin";

        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode("TestOnlyPassword123!"));

        adminRepository.saveAndFlush(admin);

        try {
            String token = jwtService.generateToken(username);
            long appointmentsBefore = appointmentRepository.count();

            mockMvc.perform(delete("/api/appointments/clear")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Admin-Action-Authorization", "invalid-test-token"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message")
                            .value("Invalid or expired authorization"));

            assertEquals(appointmentsBefore, appointmentRepository.count());

        } finally {
            adminRepository.findByUsername(username)
                    .ifPresent(adminRepository::delete);
        }
    }

    @Test
    void productCreationIsBlockedWithoutAdminToken() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized product\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void productUpdateIsBlockedWithoutAdminToken() throws Exception {
        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized update\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductListIsBlockedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/products"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminProductDeletionIsBlockedWithoutToken() throws Exception {
        mockMvc.perform(delete("/api/admin/products/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlineRefundInitiationIsBlockedWithoutAdminToken() throws Exception {
        mockMvc.perform(post("/api/admin/orders/1/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":1000,\"reason\":\"Test\",\"idempotencyKey\":\"refund-test-key-123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void managerCannotAuthorizeOnlineRazorpayRefunds() throws Exception {
        String username = "refund-manager-security-test";
        adminRepository.findByUsername(username).ifPresent(adminRepository::delete);

        Admin manager = new Admin();
        manager.setUsername(username);
        manager.setRole("MANAGER");
        manager.setPassword(passwordEncoder.encode("ManagerTestPassword123!"));
        adminRepository.saveAndFlush(manager);

        try {
            String token = jwtService.generateToken(username);
            mockMvc.perform(post("/api/admin/auth/authorize-action")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"ManagerTestPassword123!\",\"action\":\"RAZORPAY_REFUND\"}"))
                    .andExpect(status().isBadRequest());
        } finally {
            adminRepository.findByUsername(username).ifPresent(adminRepository::delete);
        }
    }

    @Test
    void disputeLedgerRequiresAdminAuthentication() throws Exception {
        mockMvc.perform(get("/api/admin/pos/payments/disputes"))
                .andExpect(status().isForbidden());
    }

    @Test
    void settlementSynchronizationRequiresAdminAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/pos/payments/settlements/sync")
                        .param("year", "2026")
                        .param("month", "11"))
                .andExpect(status().isForbidden());
    }

    @Test
    void prescriptionUploadIsPublicButFailsClosedWithoutCloudinaryCredentials() throws Exception {
        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "prescription.pdf", "application/pdf", "%PDF-1.7".getBytes()
                );
        mockMvc.perform(multipart("/api/prescriptions/upload").file(file))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void adminOrdersAreBlockedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
                .andExpect(status().isForbidden());
    }
}
