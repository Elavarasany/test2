
package nmk_backend.dto;

import java.util.Map;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class OrderItemRequest {

    @NotNull
    @Positive
    private Long productId;
    @NotNull
    @Positive
    private Long variantId;
    @NotNull
    @Positive
    private Integer quantity;

    @Size(max = 30)
    private String lensKind;
    @Size(max = 50)
    private String prescriptionType;
    @Size(max = 100)
    private String lensType;
    private Boolean photochromic;

    @Size(max = 20)
    private Map<String, Object> prescription;
    @Size(max = 255)
    private String prescriptionFileName;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getVariantId() {
        return variantId;
    }

    public void setVariantId(Long variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getLensKind() {
        return lensKind;
    }

    public void setLensKind(String lensKind) {
        this.lensKind = lensKind;
    }

    public String getPrescriptionType() {
        return prescriptionType;
    }

    public void setPrescriptionType(String prescriptionType) {
        this.prescriptionType = prescriptionType;
    }

    public String getLensType() {
        return lensType;
    }

    public void setLensType(String lensType) {
        this.lensType = lensType;
    }

    public Boolean getPhotochromic() {
        return photochromic;
    }

    public void setPhotochromic(Boolean photochromic) {
        this.photochromic = photochromic;
    }

    public Map<String, Object> getPrescription() {
        return prescription;
    }

    public void setPrescription(Map<String, Object> prescription) {
        this.prescription = prescription;
    }

    public String getPrescriptionFileName() {
        return prescriptionFileName;
    }

    public void setPrescriptionFileName(String prescriptionFileName) {
        this.prescriptionFileName = prescriptionFileName;
    }
}
