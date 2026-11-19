package vn.vnpay.qr.gateway.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "refund_ledger_entry")
class RefundLedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "refund_id", nullable = false) private Refund refund;
    @Column(nullable = false, length = 32) private String accountNo;
    @Column(nullable = false, length = 6) private String direction;
    @Column(nullable = false) private long amount;

    protected RefundLedgerEntry() { }

    RefundLedgerEntry(Refund refund, String accountNo, String direction, long amount) {
        this.refund = refund;
        this.accountNo = accountNo;
        this.direction = direction;
        this.amount = amount;
    }
}
