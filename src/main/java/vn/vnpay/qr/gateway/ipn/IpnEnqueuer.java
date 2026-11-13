package vn.vnpay.qr.gateway.ipn;

import java.time.*;
import java.time.temporal.ChronoUnit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.domain.*;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class IpnEnqueuer {
    private final IpnOutboxRepository jobs;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;
    private final Clock clock;
    public IpnEnqueuer(IpnOutboxRepository jobs, ObjectMapper mapper, HmacSigner hmac, Clock clock) {
        this.jobs = jobs; this.mapper = mapper; this.hmac = hmac; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(PaymentTxn txn, Merchant merchant) {
        if (txn.getStatus() != PaymentStatus.SUCCESS && txn.getStatus() != PaymentStatus.FAILED) {
            throw new IllegalStateException("Only terminal payment results produce IPN");
        }
        var body = mapper.createObjectNode().put("txnRef", txn.getTxnRef()).put("orderId", txn.getOrderId())
                .put("amount", txn.getAmount()).put("status", txn.getStatus().name());
        // FAILED omits both optional fields; null fields are omitted from canonicalization as in B2.
        if (txn.getBankRef() != null) body.put("bankRef", txn.getBankRef());
        if (txn.getPaidAt() != null) body.set("paidAt", mapper.valueToTree(OffsetDateTime.ofInstant(txn.getPaidAt(), BUSINESS_ZONE)));
        body.put("signature", hmac.sign(body, merchant.getSecretKey()));
        try {
            jobs.saveAndFlush(new IpnOutbox(txn.getTxnRef(), merchant.getIpnUrl(), mapper.writeValueAsString(body),
                    clock.instant().truncatedTo(ChronoUnit.MICROS)));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot serialize IPN", ex); }
    }
}
