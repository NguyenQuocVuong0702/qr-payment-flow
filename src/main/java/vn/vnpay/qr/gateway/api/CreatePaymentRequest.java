package vn.vnpay.qr.gateway.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record CreatePaymentRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,13}") String merchantId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String orderId,
        @NotNull @Min(1000) @Max(500_000_000) Long amount,
        @NotBlank @Size(max = 19) @Pattern(regexp = "[A-Za-z0-9 ]+") String description,
        @NotNull @Min(1) @Max(60) Integer expireMinutes,
        String signature) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown payment request field");
    }

    @Override
    public String toString() { return "CreatePaymentRequest[redacted]"; }
}
