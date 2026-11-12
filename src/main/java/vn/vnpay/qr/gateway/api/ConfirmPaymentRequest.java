package vn.vnpay.qr.gateway.api;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
public record ConfirmPaymentRequest(
        @NotBlank @Pattern(regexp = "[0-9]{1,32}") String payerAccount,
        @NotNull @Min(1000) @Max(500_000_000) Long amount,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown confirm field"); }
    @Override public String toString() { return "ConfirmPaymentRequest[redacted]"; }
}
