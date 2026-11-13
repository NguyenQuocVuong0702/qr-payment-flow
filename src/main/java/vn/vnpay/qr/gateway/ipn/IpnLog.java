package vn.vnpay.qr.gateway.ipn;
import java.time.Instant;
import jakarta.persistence.*;

@Entity @Table(name = "ipn_log") @org.hibernate.annotations.DynamicUpdate
public class IpnLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "txn_ref", nullable = false, length = 10) private String txnRef;
    @Column(nullable = false) private int attempt;
    @Lob @Column(name = "request_body", nullable = false) private String requestBody;
    @Column(name = "response_code", nullable = false, length = 32) private String responseCode;
    @Column(name = "http_status") private Integer httpStatus;
    @Column(name = "error_type", length = 64) private String errorType;
    @Column(name = "sent_at", nullable = false) private Instant sentAt;
    @Column(name = "finished_at") private Instant finishedAt;
    protected IpnLog() { }
    public IpnLog(IpnClaim claim, String auditBody, Instant now) {
        txnRef = claim.txnRef(); attempt = claim.attempt(); requestBody = auditBody; sentAt = now; responseCode = "IN_PROGRESS";
    }
    public String getRequestBody() { return requestBody; }
    public Long getId() { return id; }
    public void complete(IpnDeliveryResult result, Instant now) {
        responseCode = result.code(); httpStatus = result.httpStatus(); errorType = result.errorType(); finishedAt = now;
    }
}
