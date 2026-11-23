package nmk_backend.controller;

import nmk_backend.entity.WebsiteImage;
import nmk_backend.repository.WebsiteImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebsiteImageControllerTest {

    @Test
    void acceptsExistingCategoryImageSlot() {
        WebsiteImageRepository repository = mock(WebsiteImageRepository.class);
        when(repository.findByImageKey("CATEGORY_COMPUTER_GLASSES"))
                .thenReturn(Optional.empty());
        when(repository.save(any(WebsiteImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WebsiteImageController controller = new WebsiteImageController(repository);
        WebsiteImage request = new WebsiteImage();
        request.setTitle("Computer Glasses");
        request.setImageUrl("");

        var response = controller.saveWebsiteImage("CATEGORY_COMPUTER_GLASSES", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CATEGORY_COMPUTER_GLASSES", response.getBody().getImageKey());
    }

    @Test
    void rejectsUnknownWebsiteImageSlot() {
        WebsiteImageRepository repository = mock(WebsiteImageRepository.class);
        WebsiteImageController controller = new WebsiteImageController(repository);

        var response = controller.saveWebsiteImage("UNKNOWN_SLOT", new WebsiteImage());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(repository);
    }
}
