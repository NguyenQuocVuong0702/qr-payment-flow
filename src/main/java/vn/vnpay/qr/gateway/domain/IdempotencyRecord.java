package vn.vnpay.qr.gateway.domain;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.springframework.http.HttpStatus;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.api.ConfirmPaymentRequest;
import vn.vnpay.qr.gateway.service.ConfirmationOutcome;

@Entity @Table(name = "idempotency")
public class IdempotencyRecord {
    @Id @Column(name = "request_id") private UUID requestId;
    @Column(name = "txn_ref", nullable = false, length = 10) private String txnRef;
    @Column(name = "payer_account", nullable = false, length = 32) private String payerAccount;
    @Column(nullable = false) private long amount;
    @Column(name = "http_status") private Integer httpStatus;
    @Lob @Column(name = "response_body") private String responseBody;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version @Column(nullable = false) private Long version;
    protected IdempotencyRecord() { }
    public IdempotencyRecord(String txnRef, ConfirmPaymentRequest request, Instant now) {
        requestId = UUID.fromString(request.requestId()); this.txnRef = txnRef;
        payerAccount = request.payerAccount(); amount = request.amount(); createdAt = now;
    }
    public void complete(int status, String body) { httpStatus = status; responseBody = body; }
    public ConfirmationOutcome replay(String txnRef, ConfirmPaymentRequest request) {
        if (!this.txnRef.equals(txnRef) || !payerAccount.equals(request.payerAccount()) || amount != request.amount()) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "requestId was already used for a different payment");
        }
        if (httpStatus == null || responseBody == null) throw new IllegalStateException("Incomplete committed idempotency record");
        return new ConfirmationOutcome(httpStatus, responseBody);
    }
}
