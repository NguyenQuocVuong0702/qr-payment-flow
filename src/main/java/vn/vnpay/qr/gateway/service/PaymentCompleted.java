package vn.vnpay.qr.gateway.service;
/** Future B6 consumers must use @TransactionalEventListener(AFTER_COMMIT); no IPN listener exists now. */
public record PaymentCompleted(String txnRef, String status, String bankRef) { }
