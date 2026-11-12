package vn.vnpay.qr.gateway.domain;

public enum PaymentStatus {
    PENDING, PROCESSING, SUCCESS, FAILED, EXPIRED, REFUNDED;
    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING -> next == PROCESSING || next == EXPIRED;
            case PROCESSING -> next == SUCCESS || next == FAILED;
            case SUCCESS -> next == REFUNDED;
            default -> false;
        };
    }
}
