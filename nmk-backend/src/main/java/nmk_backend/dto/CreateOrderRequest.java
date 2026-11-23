package nmk_backend.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class CreateOrderRequest {

    @Size(max = 255)
    private String customerName;
    @Email
    @Size(max = 255)
    private String email;
    @Size(max = 255)
    private String phone;
    @Size(max = 1000)
    private String address;
    @Size(max = 255)
    private String city;
    @Size(max = 255)
    private String state;
    @Size(max = 255)
    private String pincode;

    @Size(max = 100)
    @Valid
    private List<@jakarta.validation.constraints.NotNull @Valid OrderItemRequest> items;

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }
}