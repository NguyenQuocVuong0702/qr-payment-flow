package vn.vnpay.qr.gateway.service;
/** In-process observation only. Durable B6 delivery is enqueued in the payment transaction, not by this event. */
public record PaymentCompleted(String txnRef, String status, String bankRef) { }
