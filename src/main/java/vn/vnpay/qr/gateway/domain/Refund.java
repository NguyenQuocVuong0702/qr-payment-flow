package vn.vnpay.qr.gateway.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.*;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.api.RefundResult;
import org.springframework.http.HttpStatus;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Entity
@Table(name = "refund_txn")
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 10) private String txnRef;
    @Column(nullable = false, length = 32) private String refundRef;
    @Column(nullable = false) private UUID requestId;
    @Column(nullable = false) private long amount;
    @Column(nullable = false) private long totalRefunded;
    @Column(nullable = false) private long remainingAmount;
    @Column(nullable = false) private Instant refundedAt;
    @OneToMany(mappedBy = "refund", cascade = CascadeType.PERSIST)
    private List<RefundLedgerEntry> entries = new ArrayList<>();

    protected Refund() { }

    public Refund(String txnRef, String refundRef, UUID requestId, long amount, long totalRefunded,
            long remainingAmount, Instant refundedAt, String debitedAccount, String creditedAccount) {
        this.txnRef = txnRef;
        this.refundRef = refundRef;
        this.requestId = requestId;
        this.amount = amount;
        this.totalRefunded = totalRefunded;
        this.remainingAmount = remainingAmount;
        this.refundedAt = refundedAt;
        entries.add(new RefundLedgerEntry(this, debitedAccount, "DEBIT", amount));
        entries.add(new RefundLedgerEntry(this, creditedAccount, "CREDIT", amount));
    }

    public RefundResult replay(long requestedAmount) {
        if (amount != requestedAmount) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Refund requestId was used for a different amount");
        }
        return new RefundResult(txnRef, refundRef, requestId, amount, totalRefunded, remainingAmount,
                remainingAmount == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED", OffsetDateTime.ofInstant(refundedAt, BUSINESS_ZONE));
    }
}
