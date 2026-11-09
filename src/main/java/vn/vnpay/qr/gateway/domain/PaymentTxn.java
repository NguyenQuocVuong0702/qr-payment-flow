package vn.vnpay.qr.gateway.domain;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "payment_txn", uniqueConstraints = @UniqueConstraint(name = "uk_payment_order", columnNames = {"merchant_id", "order_id"}))
public class PaymentTxn {
    @Id
    @Column(name = "txn_ref", nullable = false, length = 10)
    private String txnRef;

    @Column(name = "merchant_id", nullable = false, length = 13)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 32)
    private String orderId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "description", nullable = false, length = 19)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private PaymentStatus status;

    @Column(name = "qr_string", nullable = false, length = 512)
    private String qrString;

    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Column(name = "payer_account", nullable = true, length = 32)
    private String payerAccount;

    @Column(name = "bank_ref", nullable = true, length = 32)
    private String bankRef;

    @Column(name = "fail_reason", nullable = true, length = 64)
    private String failReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at", nullable = true)
    private Instant paidAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected PaymentTxn() { }

    public PaymentTxn(String txnRef, String merchantId, String orderId, long amount, String description,
            String qrString, Instant expireAt, Instant createdAt) {
        this.txnRef = txnRef;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.amount = amount;
        this.description = description;
        this.qrString = qrString;
        this.expireAt = expireAt;
        this.createdAt = createdAt;
        this.status = PaymentStatus.PENDING;
    }

    public String getTxnRef() { return txnRef; }
    public String getMerchantId() { return merchantId; }
    public String getOrderId() { return orderId; }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public PaymentStatus getStatus() { return status; }
    public String getQrString() { return qrString; }
    public Instant getExpireAt() { return expireAt; }
    public String getPayerAccount() { return payerAccount; }
    public String getBankRef() { return bankRef; }
    public String getFailReason() { return failReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
}
