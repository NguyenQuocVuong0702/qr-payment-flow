package vn.vnpay.qr.wallet.domain;
import java.time.Instant;
import jakarta.persistence.*;

@Entity @Table(name = "wallet_user")
public class WalletUser {
    @Id @Column(name = "user_id", length = 32) private String userId;
    @Column(name = "full_name", nullable = false, length = 100) private String fullName;
    @Column(name = "account_no", nullable = false, length = 32) private String accountNo;
    @Column(name = "pin_hash", nullable = false, length = 60) private String pinHash;
    @Column(name = "failed_pin_attempts", nullable = false) private int failedPinAttempts;
    @Column(name = "pin_locked_until") private Instant pinLockedUntil;
    @Version @Column(nullable = false) private Long version;
    protected WalletUser() { }
    public WalletUser(String id, String name, String account, String hash) {
        userId = id; fullName = name; accountNo = account; pinHash = hash;
    }
    public String getUserId() { return userId; }
    public String getAccountNo() { return accountNo; }
    public String getPinHash() { return pinHash; }
    public int getFailedPinAttempts() { return failedPinAttempts; }
    public Instant getPinLockedUntil() { return pinLockedUntil; }
    public void clearFailures() { failedPinAttempts = 0; pinLockedUntil = null; }
    public void failedAt(Instant now) { if (++failedPinAttempts >= 3) pinLockedUntil = now.plusSeconds(900); }
}
