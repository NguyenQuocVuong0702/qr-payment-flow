package vn.vnpay.qr.gateway.ipn;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity @Table(name = "ipn_outbox")
public class IpnOutbox {
    @Id @Column(name = "txn_ref", length = 10) private String txnRef;
    @Column(name = "ipn_url", nullable = false, length = 255) private String ipnUrl;
    @Lob @Column(name = "request_body", nullable = false) private String requestBody;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "lease_token") private UUID leaseToken;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version @Column(nullable = false) private Long version;

    protected IpnOutbox() { }
    public IpnOutbox(String ref, String url, String body, Instant now) {
        txnRef = ref; ipnUrl = url; requestBody = body; state = "READY"; nextAttemptAt = now; createdAt = now;
    }
    public String getTxnRef() { return txnRef; }
    public String getState() { return state; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public boolean ready(Instant now) { return state.equals("READY") && !nextAttemptAt.isAfter(now); }
    public boolean abandoned(Instant now) { return state.equals("LEASED") && !leaseUntil.isAfter(now); }
    public IpnClaim claim(Instant now, Instant until) {
        if (!ready(now) || attemptCount >= 3) throw new IllegalStateException("IPN is not ready");
        state = "LEASED"; nextAttemptAt = null; leaseToken = UUID.randomUUID(); leaseUntil = until;
        return new IpnClaim(txnRef, ++attemptCount, leaseToken, ipnUrl, requestBody);
    }
    public boolean owns(IpnClaim claim) {
        return state.equals("LEASED") && claim.attempt() == attemptCount && claim.token().equals(leaseToken);
    }
    public void finish(boolean delivered, Instant retryAt) {
        state = delivered ? "DELIVERED" : attemptCount >= 3 ? "EXHAUSTED" : "READY";
        nextAttemptAt = state.equals("READY") ? retryAt : null; leaseToken = null; leaseUntil = null;
    }
}
