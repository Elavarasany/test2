package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.entity.WebsiteImage;
import nmk_backend.repository.WebsiteImageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class WebsiteImageController {

    private static final Set<String> ALLOWED_IMAGE_KEYS = Set.of(
            "HERO", "LOGO", "STORE",
            "CATEGORY_EYEGLASSES",
            "CATEGORY_SUNGLASSES",
            "CATEGORY_CONTACT_LENSES",
            "CATEGORY_COMPUTER_GLASSES"
    );

    private final WebsiteImageRepository websiteImageRepository;

    public WebsiteImageController(WebsiteImageRepository websiteImageRepository) {
        this.websiteImageRepository = websiteImageRepository;
    }

    @GetMapping("/website-images")
    public List<WebsiteImage> getWebsiteImages() {
        return websiteImageRepository.findAll();
    }

    @GetMapping("/admin/website-images")
    public List<WebsiteImage> getAdminWebsiteImages() {
        return websiteImageRepository.findAll();
    }

    @PutMapping("/admin/website-images/{imageKey}")
    public ResponseEntity<WebsiteImage> saveWebsiteImage(
            @PathVariable String imageKey,
            @Valid @RequestBody WebsiteImage updatedImage) {

        if (!ALLOWED_IMAGE_KEYS.contains(imageKey)) {
            return ResponseEntity.badRequest().build();
        }

        WebsiteImage image = websiteImageRepository
                .findByImageKey(imageKey)
                .orElseGet(WebsiteImage::new);

        image.setImageKey(imageKey);
        image.setTitle(updatedImage.getTitle());
        image.setImageUrl(updatedImage.getImageUrl());

        return ResponseEntity.ok(websiteImageRepository.save(image));
    }
}
