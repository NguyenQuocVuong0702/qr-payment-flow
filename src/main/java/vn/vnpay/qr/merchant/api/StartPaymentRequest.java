package vn.vnpay.qr.merchant.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StartPaymentRequest(@NotNull @Min(1) @Max(60) Integer expireMinutes) {
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Only expireMinutes is accepted");
    }
}
