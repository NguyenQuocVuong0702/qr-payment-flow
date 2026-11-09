package vn.vnpay.qr.common.error;

import java.time.OffsetDateTime;

public record ApiError(String errorCode, String message, OffsetDateTime timestamp) {
}
