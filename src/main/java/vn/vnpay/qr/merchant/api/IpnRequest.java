package vn.vnpay.qr.merchant.api;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IpnRequest(String txnRef, String orderId, Long amount, String status, String bankRef, String paidAt, String signature) {
    @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown IPN field"); }
    @Override public String toString() { return "IpnRequest[redacted]"; }
}
