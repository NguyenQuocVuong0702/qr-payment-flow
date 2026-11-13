package vn.vnpay.qr.merchant.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders")
public class Order {
    @Id
    @Column(name = "order_id", length = 32, nullable = false, updatable = false)
    private String orderId;

    @Column(name = "merchant_id", length = 32, nullable = false, updatable = false)
    private String merchantId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(length = 19, nullable = false, updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private OrderStatus status;

    @Column(name = "txn_ref", length = 32, unique = true)
    private String txnRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Order() {
    }

    public Order(String orderId, String merchantId, long amount, String description, Instant createdAt) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.description = description;
        this.status = OrderStatus.CREATED;
        this.createdAt = createdAt;
    }

    public String getOrderId() { return orderId; }

    public void attachPayment(String txnRef) {
        if (status == OrderStatus.WAITING_PAYMENT && txnRef.equals(this.txnRef)) {
            return;
        }
        if (status != OrderStatus.CREATED || this.txnRef != null) {
            throw new vn.vnpay.qr.common.error.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "ORDER_CONFLICT", "Order is already linked to another payment or state");
        }
        this.txnRef = txnRef;
        this.status = OrderStatus.WAITING_PAYMENT;
    }
    public String getMerchantId() { return merchantId; }
    public void expire() {
        if (status != OrderStatus.WAITING_PAYMENT) throw new IllegalStateException("Order is not waiting for payment");
        status = OrderStatus.EXPIRED;
    }
    public void confirmPayment(boolean success, Instant paidAt) {
        if (status != OrderStatus.WAITING_PAYMENT) throw new IllegalStateException("Order is not waiting for payment");
        status = success ? OrderStatus.PAID : OrderStatus.PAYMENT_FAILED;
        this.paidAt = success ? paidAt : null;
    }
    public long getAmount() { return amount; }
    public String getDescription() { return description; }
    public OrderStatus getStatus() { return status; }
    public String getTxnRef() { return txnRef; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
}
