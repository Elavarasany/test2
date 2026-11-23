package nmk_backend.controller;

import nmk_backend.service.PrescriptionCloudinaryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionUploadController {
    private final PrescriptionCloudinaryService storage;

    public PrescriptionUploadController(PrescriptionCloudinaryService storage) {
        this.storage = storage;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(storage.upload(file));
    }
}
