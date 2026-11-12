package vn.vnpay.qr.gateway.service;

public record LookupCaller(boolean wallet, String merchantId, String signingKey) {
    @Override public String toString() { return "LookupCaller[redacted]"; }
}
