package nmk_backend;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import nmk_backend.entity.MarketingBanner;
import nmk_backend.entity.OfferBar;
import nmk_backend.entity.WebsiteImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketingContentValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsSupportedBannerPlacementsAndSafeDestinations() {
        MarketingBanner banner = new MarketingBanner();
        banner.setTitle("Summer collection");
        banner.setImageUrl("https://cdn.example.com/banner.webp");
        banner.setTargetUrl("/products");
        banner.setPlacement("HOME_HERO");
        banner.setDisplayOrder(0);

        assertTrue(validator.validate(banner).isEmpty());
    }

    @Test
    void rejectsUnsupportedBannerPlacementUnsafeDestinationAndNegativeOrder() {
        MarketingBanner banner = new MarketingBanner();
        banner.setTitle("Summer collection");
        banner.setImageUrl("https://cdn.example.com/banner.webp");
        banner.setTargetUrl("javascript:alert(1)");
        banner.setPlacement("FOOTER");
        banner.setDisplayOrder(-1);

        assertFalse(validator.validate(banner).isEmpty());
    }

    @Test
    void rejectsInvalidOfferColorsAndUnsafeDestination() {
        OfferBar offer = new OfferBar();
        offer.setMessage("Limited time offer");
        offer.setBackgroundColor("red");
        offer.setTextColor("#FFFFFF");
        offer.setTargetUrl("//malicious.example");

        assertFalse(validator.validate(offer).isEmpty());
    }

    @Test
    void acceptsWebsiteImageRemovalWithEmptyUrlAndSupportedSlot() {
        WebsiteImage image = new WebsiteImage();
        image.setImageKey("STORE");
        image.setTitle("Store Image");
        image.setImageUrl("");

        assertTrue(validator.validate(image).isEmpty());
    }

    @Test
    void rejectsUnsafeWebsiteImageUrl() {
        WebsiteImage image = new WebsiteImage();
        image.setImageKey("STORE");
        image.setTitle("Store Image");
        image.setImageUrl("javascript:alert(1)");

        assertFalse(validator.validate(image).isEmpty());
    }
    @Test
    void acceptsExistingLongCategoryImageKeys() {
        WebsiteImage image = new WebsiteImage();
        image.setImageKey("CATEGORY_COMPUTER_GLASSES");
        image.setTitle("Computer Glasses");
        image.setImageUrl("");

        assertTrue(validator.validate(image).isEmpty());
    }

}
