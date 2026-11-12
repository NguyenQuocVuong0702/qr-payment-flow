package vn.vnpay.qr.gateway.bank;
import java.time.Instant;
import jakarta.persistence.*;
@Entity @Table(name = "ledger_entry")
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "txn_ref", nullable = false, length = 10) private String txnRef;
    @Column(name = "bank_ref", nullable = false, length = 32) private String bankRef;
    @Column(name = "account_no", nullable = false, length = 32) private String accountNo;
    @Column(nullable = false, length = 6) private String direction;
    @Column(nullable = false) private long amount;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected LedgerEntry() { }
    public LedgerEntry(String txn, String bank, String account, String direction, long amount, Instant now) {
        txnRef = txn; bankRef = bank; accountNo = account; this.direction = direction; this.amount = amount; createdAt = now;
    }
}
