
package nmk_backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class VerifyPaymentRequest {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "order_[A-Za-z0-9]+")
    private String razorpayOrderId;
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "pay_[A-Za-z0-9]+")
    private String razorpayPaymentId;
    @NotBlank
    @Size(min = 64, max = 64)
    @Pattern(regexp = "[A-Fa-f0-9]{64}")
    private String razorpaySignature;

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public void setRazorpayOrderId(String razorpayOrderId) {
        this.razorpayOrderId = razorpayOrderId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public void setRazorpayPaymentId(String razorpayPaymentId) {
        this.razorpayPaymentId = razorpayPaymentId;
    }

    public String getRazorpaySignature() {
        return razorpaySignature;
    }

    public void setRazorpaySignature(String razorpaySignature) {
        this.razorpaySignature = razorpaySignature;
    }
}
