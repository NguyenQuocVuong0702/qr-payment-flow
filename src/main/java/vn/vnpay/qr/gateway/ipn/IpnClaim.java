package vn.vnpay.qr.gateway.ipn;
import java.util.UUID;
public record IpnClaim(String txnRef, int attempt, UUID token, String url, String requestBody) {
    @Override public String toString() { return "IpnClaim[redacted]"; }
}
