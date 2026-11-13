package vn.vnpay.qr.merchant.service;

import java.time.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.merchant.api.*;
import vn.vnpay.qr.merchant.config.*;
import vn.vnpay.qr.merchant.domain.OrderStatus;
import vn.vnpay.qr.merchant.repository.OrderRepository;

@Service
public class MerchantIpnService {
    private final OrderRepository orders;
    private final MerchantProperties merchant;
    private final MerchantGatewayProperties gateway;
    private final HmacSigner hmac;
    private final ObjectMapper mapper;
    public MerchantIpnService(OrderRepository orders, MerchantProperties merchant, MerchantGatewayProperties gateway,
            HmacSigner hmac, ObjectMapper mapper) {
        this.orders = orders; this.merchant = merchant; this.gateway = gateway; this.hmac = hmac; this.mapper = mapper;
    }
    @Transactional
    public IpnResponse receive(IpnRequest request) {
        if (!hmac.verify(mapper.valueToTree(request), gateway.secretKey(), request.signature())) return new IpnResponse("97", "Invalid signature");
        if (request.orderId() == null) return notFound();
        var found = orders.findForUpdate(request.orderId()).filter(order -> order.getMerchantId().equals(merchant.id()));
        if (found.isEmpty()) return notFound();
        var order = found.get();
        if (request.amount() == null || request.amount() != order.getAmount()) return new IpnResponse("04", "Invalid amount");
        if (order.getTxnRef() == null || !order.getTxnRef().equals(request.txnRef())) return notFound();
        Instant paidAt = null;
        boolean success = "SUCCESS".equals(request.status());
        try {
            if (success) {
                if (request.bankRef() == null || request.bankRef().isBlank() || request.bankRef().length() > 32 || request.paidAt() == null) throw invalid();
                paidAt = OffsetDateTime.parse(request.paidAt()).toInstant();
            } else if (!"FAILED".equals(request.status()) || request.bankRef() != null || request.paidAt() != null) throw invalid();
        } catch (DateTimeException ex) { throw invalid(); }
        if (order.getStatus() != OrderStatus.WAITING_PAYMENT) return new IpnResponse("02", "Order already confirmed");
        order.confirmPayment(success, paidAt);
        orders.flush();
        return new IpnResponse("00", "Confirm success");
    }
    private IpnResponse notFound() { return new IpnResponse("01", "Order not found"); }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid IPN result payload"); }
}
