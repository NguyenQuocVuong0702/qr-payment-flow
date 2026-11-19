package vn.vnpay.qr.gateway.service;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.time.BusinessDay;
import vn.vnpay.qr.gateway.api.ReconciliationResponse;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class GatewayReconciliationService {
    private final PaymentTxnRepository payments;
    public GatewayReconciliationService(PaymentTxnRepository payments) { this.payments = payments; }
    @Transactional(readOnly = true)
    public ReconciliationResponse get(String merchantId, BusinessDay day) {
        // B8 compares original collections on paidAt. Refunds are separate money movements.
        var transactions = payments.findCapturedForDay(merchantId, day.start(), day.end()).stream()
                .map(txn -> new ReconciliationResponse.Transaction(txn.getTxnRef(), txn.getOrderId(), txn.getAmount(),
                        "SUCCESS", txn.getBankRef(), OffsetDateTime.ofInstant(txn.getPaidAt(), BUSINESS_ZONE))).toList();
        long total = 0;
        for (var txn : transactions) total = Math.addExact(total, txn.amount());
        return new ReconciliationResponse(merchantId, day.date().toString(), transactions.size(), total, transactions);
    }
}
