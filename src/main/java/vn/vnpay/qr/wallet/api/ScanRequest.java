package vn.vnpay.qr.wallet.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ScanRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String userId, String qrString) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown scan field"); }
    @Override public String toString() { return "ScanRequest[redacted]"; }
}
