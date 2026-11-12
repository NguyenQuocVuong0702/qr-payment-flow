package vn.vnpay.qr.wallet.api;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record WalletPaymentRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String userId,
        @NotBlank @Pattern(regexp = "TX[0-9]{8}") String txnRef,
        @NotNull @Min(1000) @Max(500_000_000) Long amount,
        @NotNull @Size(max = 72) String pin,
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String requestId) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown payment field"); }
    @Override public String toString() { return "WalletPaymentRequest[redacted]"; }
}
