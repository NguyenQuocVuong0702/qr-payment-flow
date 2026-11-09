package vn.vnpay.qr.merchant.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotNull @Min(1000) @Max(500_000_000) Long amount,
        @NotBlank @Size(min = 1, max = 19) @Pattern(regexp = "[A-Za-z0-9 ]+") String description) {
}
