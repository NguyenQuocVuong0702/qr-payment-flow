package vn.vnpay.qr.merchant.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record ReconcileRequest(String date) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown reconciliation field"); }
}
