package nmk_backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PrescriptionCloudinaryService {
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpg", "jpeg", "png", "webp", "pdf");

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final Cloudinary cloudinary;

    public PrescriptionCloudinaryService(
            @Value("${cloudinary.cloud-name:ydkweahp}") String cloudName,
            @Value("${cloudinary.api-key:}") String apiKey,
            @Value("${cloudinary.api-secret:}") String apiSecret
    ) {
        this.cloudName = cloudName == null ? "" : cloudName.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                "cloud_name", this.cloudName,
                "api_key", this.apiKey,
                "api_secret", this.apiSecret,
                "secure", true
        ));
    }

    public Map<String, Object> upload(MultipartFile file) {
        requireConfigured();
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a prescription file smaller than 5 MB.");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Choose a prescription file smaller than 5 MB.");
            }
            String detectedFormat = detectFormat(bytes);
            String publicId = "nmk-prescriptions/" + UUID.randomUUID().toString().replace("-", "");
            Map result = cloudinary.uploader().upload(bytes, ObjectUtils.asMap(
                    "resource_type", "auto",
                    "type", "authenticated",
                    "public_id", publicId,
                    "use_filename", false,
                    "unique_filename", false,
                    "overwrite", false,
                    "allowed_formats", "jpg,jpeg,png,webp,pdf"
            ));
            String returnedId = String.valueOf(result.get("public_id"));
            String resourceType = String.valueOf(result.get("resource_type"));
            String returnedFormat = String.valueOf(result.get("format")).toLowerCase(Locale.ROOT);
            String version = String.valueOf(result.get("version"));
            String signature = String.valueOf(result.get("signature"));
            boolean resourceTypeMatches = "pdf".equals(detectedFormat)
                    ? ("image".equals(resourceType) || "raw".equals(resourceType))
                    : "image".equals(resourceType);
            boolean responseFormatMatches = ALLOWED_FORMATS.contains(returnedFormat)
                    && canonicalFormat(detectedFormat).equals(canonicalFormat(returnedFormat));
            boolean responseMetadataValid = version.matches("[0-9]{1,20}")
                    && signature.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}");
            if (!publicId.equals(returnedId)
                    || !resourceTypeMatches
                    || !responseFormatMatches
                    || !responseMetadataValid
                    || !cloudinary.verifyApiResponseSignature(returnedId, version, signature)) {
                try {
                    cloudinary.uploader().destroy(returnedId, ObjectUtils.asMap(
                            "resource_type", resourceType, "type", "authenticated"
                    ));
                } catch (Exception ignored) {
                    // Invalid uploads remain inaccessible because authenticated delivery is protected.
                }
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prescription storage returned an invalid asset.");
            }
            long numericVersion = Long.parseLong(version);
            return Map.of(
                    "publicId", returnedId,
                    "resourceType", resourceType,
                    "format", returnedFormat,
                    "version", numericVersion,
                    "signature", signature,
                    "assetProof", createAssetProof(returnedId, resourceType, returnedFormat, numericVersion),
                    "imageName", safeFileName(file.getOriginalFilename())
            );
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read the selected prescription file.");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prescription storage is temporarily unavailable.");
        }
    }

    public boolean verifyUploadedAsset(Map<String, Object> asset) {
        if (!isValidAssetShape(asset) || cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) return false;
        try {
            String publicId = String.valueOf(asset.get("publicId"));
            String resourceType = String.valueOf(asset.get("resourceType"));
            String format = String.valueOf(asset.get("format")).toLowerCase(Locale.ROOT);
            long version = Long.parseLong(String.valueOf(asset.get("version")));
            boolean cloudinarySignatureValid = cloudinary.verifyApiResponseSignature(
                    publicId, String.valueOf(version), String.valueOf(asset.get("signature")));
            byte[] expected = createAssetProof(publicId, resourceType, format, version)
                    .getBytes(StandardCharsets.US_ASCII);
            byte[] actual = String.valueOf(asset.get("assetProof")).getBytes(StandardCharsets.US_ASCII);
            return cloudinarySignatureValid && MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public String createPrivateDownloadUrl(Map<String, Object> asset) {
        requireConfigured();
        if (!verifyUploadedAsset(asset)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prescription asset details are invalid.");
        }
        try {
            long expiresAt = System.currentTimeMillis() / 1000L + 300L;
            return cloudinary.privateDownload(
                    String.valueOf(asset.get("publicId")),
                    String.valueOf(asset.get("format")),
                    ObjectUtils.asMap(
                            "resource_type", String.valueOf(asset.get("resourceType")),
                            "type", "authenticated",
                            "expires_at", expiresAt,
                            "attachment", "pdf".equalsIgnoreCase(String.valueOf(asset.get("format")))
                    )
            );
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create a secure prescription link.");
        }
    }

    private boolean isValidAssetShape(Map<String, Object> asset) {
        if (asset == null) return false;
        String id = String.valueOf(asset.getOrDefault("publicId", ""));
        String resourceType = String.valueOf(asset.getOrDefault("resourceType", ""));
        String format = String.valueOf(asset.getOrDefault("format", "")).toLowerCase(Locale.ROOT);
        String version = String.valueOf(asset.getOrDefault("version", ""));
        String signature = String.valueOf(asset.getOrDefault("signature", ""));
        return id.matches("nmk-prescriptions/[a-f0-9]{32}")
                && ("image".equals(resourceType) || "raw".equals(resourceType))
                && ALLOWED_FORMATS.contains(format)
                && version.matches("[0-9]{1,20}")
                && signature.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
                && String.valueOf(asset.getOrDefault("assetProof", "")).matches("[a-fA-F0-9]{64}");
    }

    private String createAssetProof(String publicId, String resourceType, String format, long version) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = publicId + "|" + resourceType + "|" + format + "|" + version;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify private prescription metadata.", exception);
        }
    }

    private void requireConfigured() {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Secure prescription storage is not configured.");
        }
    }

    private String canonicalFormat(String format) {
        return "jpeg".equalsIgnoreCase(format) ? "jpg" : format.toLowerCase(Locale.ROOT);
    }

    private String detectFormat(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) return "jpg";
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "png";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') return "pdf";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selected file is not a supported JPG, PNG, WEBP or PDF.");
    }

    public String safeFileName(String value) {
        if (value == null) return "prescription";
        String clean = value.replaceAll("[\\\\/\\r\\n\\p{Cntrl}]", "_").trim();
        if (clean.isBlank()) return "prescription";
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }
}
