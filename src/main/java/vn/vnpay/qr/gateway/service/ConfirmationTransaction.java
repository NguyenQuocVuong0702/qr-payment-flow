package vn.vnpay.qr.gateway.service;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiError;
import vn.vnpay.qr.gateway.api.*;
import vn.vnpay.qr.gateway.bank.BankTransferService;
import vn.vnpay.qr.gateway.domain.*;
import vn.vnpay.qr.gateway.repository.*;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class ConfirmationTransaction {
    private final PaymentTxnRepository payments;
    private final IdempotencyRepository requests;
    private final MerchantRepository merchants;
    private final BankTransferService bank;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final vn.vnpay.qr.gateway.ipn.IpnEnqueuer ipn;
    public ConfirmationTransaction(PaymentTxnRepository payments, IdempotencyRepository requests, MerchantRepository merchants,
            BankTransferService bank, ObjectMapper mapper, Clock clock, ApplicationEventPublisher events,
            vn.vnpay.qr.gateway.ipn.IpnEnqueuer ipn) {
        this.payments = payments; this.requests = requests; this.merchants = merchants; this.bank = bank;
        this.mapper = mapper; this.clock = clock; this.events = events; this.ipn = ipn;
    }
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ConfirmationOutcome execute(String txnRef, ConfirmPaymentRequest request) {
        var existing = requests.findById(UUID.fromString(request.requestId()));
        if (existing.isPresent()) return existing.get().replay(txnRef, request);
        // Reserve the UUID before payment/account locks. Concurrent use blocks on this unique insert.
        // The reservation is invisible until commit, and is rolled back on every unexpected exception.
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var record = requests.saveAndFlush(new IdempotencyRecord(txnRef, request, now));
        var found = payments.findForUpdate(txnRef);
        if (found.isEmpty()) return error(record, 404, "TXN_NOT_FOUND", "Payment transaction not found", now);
        var txn = found.get();
        if (txn.getStatus() == PaymentStatus.EXPIRED) return error(record, 422, "TXN_EXPIRED", "Payment transaction expired", now);
        if (txn.getStatus() != PaymentStatus.PENDING) return error(record, 422, "TXN_NOT_PAYABLE", "Payment transaction is not pending", now);
        // Read the clock after acquiring the payment lock, not before a potentially long wait.
        var checkedAt = clock.instant();
        now = checkedAt.truncatedTo(ChronoUnit.SECONDS);
        if (!checkedAt.isBefore(txn.getExpireAt())) {
            txn.expire(); return error(record, 422, "TXN_EXPIRED", "Payment transaction expired", now);
        }
        if (txn.getAmount() != request.amount()) return error(record, 422, "AMOUNT_MISMATCH", "Amount differs from Gateway", now);
        var merchant = merchants.findById(txn.getMerchantId()).orElseThrow();
        if (request.payerAccount().equals(merchant.getAccountNo())) return error(record, 422, "INVALID_TRANSFER", "Source and destination must differ", now);
        txn.startProcessing(request.payerAccount());
        var transfer = bank.transfer(txnRef, request.payerAccount(), merchant.getAccountNo(), txn.getAmount(), now);
        if (transfer.errorCode() != null) {
            txn.fail(transfer.errorCode());
            ipn.enqueue(txn, merchant);
            events.publishEvent(new PaymentCompleted(txnRef, "FAILED", null));
            if (transfer.errorCode().equals("ACCOUNT_NOT_FOUND")) return error(record, 404, "ACCOUNT_NOT_FOUND", "Bank account not found", now);
            return finish(record, 200, new ConfirmPaymentResult(txnRef, "FAILED", txn.getAmount(), null, null,
                    "Thanh toán thất bại", transfer.errorCode()));
        }
        txn.succeed(transfer.bankRef(), now);
        ipn.enqueue(txn, merchant);
        events.publishEvent(new PaymentCompleted(txnRef, "SUCCESS", transfer.bankRef()));
        return finish(record, 200, new ConfirmPaymentResult(txnRef, "SUCCESS", txn.getAmount(), transfer.bankRef(),
                OffsetDateTime.ofInstant(now, BUSINESS_ZONE), "Thanh toán thành công", null));
    }
    @Transactional(readOnly = true)
    public ConfirmationOutcome winner(String txnRef, ConfirmPaymentRequest request) {
        return requests.findById(UUID.fromString(request.requestId())).map(row -> row.replay(txnRef, request)).orElse(null);
    }
    private ConfirmationOutcome error(IdempotencyRecord record, int status, String code, String message, Instant now) {
        return finish(record, status, new ApiError(code, message, OffsetDateTime.ofInstant(now, BUSINESS_ZONE)));
    }
    private ConfirmationOutcome finish(IdempotencyRecord record, int status, Object response) {
        try {
            String json = mapper.writeValueAsString(response);
            record.complete(status, json);
            requests.flush(); // Includes payment, account, ledger and response updates in this same transaction.
            return new ConfirmationOutcome(status, json);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize confirmation outcome", ex); }
    }
}
