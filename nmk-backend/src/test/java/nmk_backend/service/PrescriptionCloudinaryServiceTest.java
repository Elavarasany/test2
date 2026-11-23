package nmk_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrescriptionCloudinaryServiceTest {

    @Test
    void rejectsUnsupportedFileContentEvenWhenExtensionCouldLookValid() {
        PrescriptionCloudinaryService service =
                new PrescriptionCloudinaryService("cloud", "key", "secret");
        MockMultipartFile file = new MockMultipartFile(
                "file", "prescription.jpg", "image/jpeg", "not-an-image".getBytes()
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> service.upload(file)
        );
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsFilesOverFiveMegabytesBeforeCallingCloudinary() {
        PrescriptionCloudinaryService service =
                new PrescriptionCloudinaryService("cloud", "key", "secret");
        MockMultipartFile file = new MockMultipartFile(
                "file", "large.pdf", "application/pdf", new byte[(5 * 1024 * 1024) + 1]
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> service.upload(file)
        );
        assertEquals(400, error.getStatusCode().value());
    }

    @Test
    void rejectsUntrustedOrMalformedAssetMetadata() {
        PrescriptionCloudinaryService service =
                new PrescriptionCloudinaryService("cloud", "key", "secret");

        assertFalse(service.verifyUploadedAsset(Map.of(
                "publicId", "somewhere-else/private-file",
                "resourceType", "image",
                "format", "jpg",
                "version", 123L,
                "signature", "a".repeat(40)
        )));
    }

    @Test
    void rejectsPrivateUploadWhenCredentialsAreMissing() {
        PrescriptionCloudinaryService service =
                new PrescriptionCloudinaryService("ydkweahp", "", "");
        MockMultipartFile file = new MockMultipartFile(
                "file", "prescription.pdf", "application/pdf", "%PDF-1.7".getBytes()
        );

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class, () -> service.upload(file)
        );
        assertEquals(503, error.getStatusCode().value());
    }
}
