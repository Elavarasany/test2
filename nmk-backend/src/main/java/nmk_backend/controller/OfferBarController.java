package nmk_backend.controller;

import jakarta.validation.Valid;
import nmk_backend.entity.OfferBar;
import nmk_backend.repository.OfferBarRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api")
public class OfferBarController {

    private final OfferBarRepository offerBarRepository;

    public OfferBarController(OfferBarRepository offerBarRepository) {
        this.offerBarRepository = offerBarRepository;
    }

    @GetMapping("/marketing/offer")
    public ResponseEntity<OfferBar> getCurrentOffer() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        return offerBarRepository.findCurrentlyValidOrdered(now)
                .stream()
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/admin/marketing/offers")
    public List<OfferBar> getAllOffers() {
        return offerBarRepository.findAll();
    }

    @PostMapping("/admin/marketing/offers")
    public ResponseEntity<OfferBar> createOffer(@Valid @RequestBody OfferBar offer) {
        if (!hasValidDateRange(offer)) {
            return ResponseEntity.badRequest().build();
        }
        offer.setId(null);
        return ResponseEntity.ok(offerBarRepository.save(offer));
    }

    @PutMapping("/admin/marketing/offers/{id}")
    public ResponseEntity<OfferBar> updateOffer(
            @PathVariable Long id,
            @Valid @RequestBody OfferBar updatedOffer) {

        if (!hasValidDateRange(updatedOffer)) {
            return ResponseEntity.badRequest().build();
        }

        return offerBarRepository.findById(id)
                .map(offer -> {
                    offer.setMessage(updatedOffer.getMessage());
                    offer.setBackgroundColor(updatedOffer.getBackgroundColor());
                    offer.setTextColor(updatedOffer.getTextColor());
                    offer.setTargetUrl(updatedOffer.getTargetUrl());
                    offer.setStartDateTime(updatedOffer.getStartDateTime());
                    offer.setEndDateTime(updatedOffer.getEndDateTime());
                    offer.setActive(updatedOffer.isActive());
                    return ResponseEntity.ok(offerBarRepository.save(offer));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/admin/marketing/offers/{id}")
    public ResponseEntity<Void> deleteOffer(@PathVariable Long id) {
        if (!offerBarRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        offerBarRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private boolean hasValidDateRange(OfferBar offer) {
        return offer.getStartDateTime() == null
                || offer.getEndDateTime() == null
                || offer.getEndDateTime().isAfter(offer.getStartDateTime());
    }
}
