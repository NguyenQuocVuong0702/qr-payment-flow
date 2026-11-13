package vn.vnpay.qr.gateway.ipn;
public record IpnDeliveryResult(boolean delivered, String code, Integer httpStatus, String errorType) { }
