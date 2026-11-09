package vn.vnpay.qr.gateway.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.api.CreatePaymentRequest;
import vn.vnpay.qr.gateway.api.CreatePaymentResponse;
import vn.vnpay.qr.gateway.domain.PaymentTxn;
import vn.vnpay.qr.gateway.qr.DynamicQrBuilder;
import vn.vnpay.qr.gateway.repository.MerchantRepository;
import vn.vnpay.qr.gateway.repository.PaymentTxnRepository;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class PaymentCreationTransaction {
    private final MerchantRepository merchants;
    private final PaymentTxnRepository payments;
    private final JdbcTemplate jdbc;
    private final DynamicQrBuilder qr;
    private final HmacSigner hmac;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PaymentCreationTransaction(MerchantRepository merchants, PaymentTxnRepository payments,
            JdbcTemplate jdbc, DynamicQrBuilder qr, HmacSigner hmac, ObjectMapper mapper, Clock clock) {
        this.merchants = merchants;
        this.payments = payments;
        this.jdbc = jdbc;
        this.qr = qr;
        this.hmac = hmac;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public CreatePaymentResponse create(CreatePaymentRequest request) {
        var merchant = merchants.findById(request.merchantId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", "Merchant not found"));
        if (!hmac.verify(mapper.valueToTree(request), merchant.getSecretKey(), request.signature())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid signature");
        }
        var existing = payments.findByMerchantIdAndOrderId(request.merchantId(), request.orderId());
        if (existing.isPresent()) {
            return replay(existing.get(), request.amount());
        }
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expireAt = now.plus(request.expireMinutes(), ChronoUnit.MINUTES);
        Long sequence = jdbc.queryForObject("SELECT NEXT VALUE FOR gateway_txn_seq", Long.class);
        String ref = "TX" + String.format(Locale.ROOT, "%08d", sequence);
        var txn = new PaymentTxn(ref, request.merchantId(), request.orderId(), request.amount(),
                request.description(), qr.build(merchant, ref, request.amount(), request.description(), expireAt),
                expireAt, now);
        payments.saveAndFlush(txn);
        return response(txn);
    }

    @Transactional(readOnly = true)
    public CreatePaymentResponse findWinner(CreatePaymentRequest request) {
        return payments.findByMerchantIdAndOrderId(request.merchantId(), request.orderId())
                .map(txn -> replay(txn, request.amount())).orElse(null);
    }

    private CreatePaymentResponse replay(PaymentTxn txn, long amount) {
        if (txn.getAmount() != amount) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_CONFLICT", "Order amount conflicts with existing payment");
        }
        return response(txn);
    }

    private CreatePaymentResponse response(PaymentTxn txn) {
        return new CreatePaymentResponse(txn.getTxnRef(), txn.getOrderId(), txn.getAmount(), txn.getStatus(),
                OffsetDateTime.ofInstant(txn.getExpireAt(), BUSINESS_ZONE), txn.getQrString());
    }
}
