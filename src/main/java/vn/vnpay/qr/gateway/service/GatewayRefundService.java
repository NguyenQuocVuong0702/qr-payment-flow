package vn.vnpay.qr.gateway.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.api.RefundRequest;
import vn.vnpay.qr.gateway.api.RefundResult;
import vn.vnpay.qr.gateway.bank.BankTransferService;
import vn.vnpay.qr.gateway.bank.LedgerEntryRepository;
import vn.vnpay.qr.gateway.domain.PaymentStatus;
import vn.vnpay.qr.gateway.domain.Refund;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import vn.vnpay.qr.gateway.repository.RefundRepository;

@Service
public class GatewayRefundService {
    private final PaymentTxnRepository payments;
    private final RefundRepository refunds;
    private final LedgerEntryRepository ledger;
    private final BankTransferService bank;
    private final Clock clock;

    public GatewayRefundService(PaymentTxnRepository payments, RefundRepository refunds, LedgerEntryRepository ledger,
            BankTransferService bank, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.bank = bank;
        this.clock = clock;
    }

    @Transactional
    public RefundResult refund(String merchantId, String txnRef, RefundRequest request) {
        var payment = payments.findForUpdate(txnRef).filter(txn -> txn.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TXN_NOT_FOUND", "Payment transaction not found"));
        var requestId = UUID.fromString(request.requestId());
        var existing = refunds.findByTxnRefAndRequestId(txnRef, requestId);
        if (existing.isPresent()) return existing.get().replay(request.amount());
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_AMOUNT_EXCEEDED", "Payment has been fully refunded");
        }
        if (payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TXN_NOT_REFUNDABLE", "Refund requires a successful payment");
        }

        long total = refunds.totalFor(txnRef);
        long remaining = payment.getAmount() - total - request.amount();
        if (remaining < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_AMOUNT_EXCEEDED", "Refund amount exceeds the remaining payment amount");
        }
        // Use the original credited account, even if the merchant's settlement account was changed later.
        String debited = ledger.findCreditedAccount(payment.getBankRef())
                .orElseThrow(() -> new IllegalStateException("Missing credit entry for successful payment"));
        String credited = payment.getPayerAccount();
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var transfer = bank.reverse(debited, credited, request.amount(), now);
        if (transfer.errorCode() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, transfer.errorCode(), "Bank declined refund");
        }

        var refund = new Refund(txnRef, transfer.bankRef(), requestId, request.amount(), total + request.amount(), remaining,
                now, debited, credited);
        if (remaining == 0) payment.markRefunded();
        refunds.saveAndFlush(refund);
        return refund.replay(request.amount());
    }
}
