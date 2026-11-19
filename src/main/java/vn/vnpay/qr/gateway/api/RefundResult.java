package vn.vnpay.qr.gateway.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundResult(String txnRef, String refundRef, UUID requestId, long amount, long totalRefunded,
        long remainingAmount, String status, OffsetDateTime refundedAt) { }
