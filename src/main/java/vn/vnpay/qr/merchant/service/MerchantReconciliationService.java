package vn.vnpay.qr.merchant.service;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.common.reconciliation.ReconciliationCsv;
import vn.vnpay.qr.common.time.BusinessDay;
import vn.vnpay.qr.merchant.api.*;
import vn.vnpay.qr.merchant.client.GatewayPaymentClient;
import vn.vnpay.qr.merchant.client.GatewayReconciliation;
import vn.vnpay.qr.merchant.config.MerchantGatewayProperties;
import vn.vnpay.qr.merchant.config.MerchantProperties;
import vn.vnpay.qr.merchant.domain.OrderStatus;

@Service
public class MerchantReconciliationService {
    private final MerchantReconciliationStore store;
    private final GatewayPaymentClient gateway;
    private final MerchantProperties merchant;
    private final MerchantGatewayProperties gatewayProperties;
    private final ReconciliationCsv csv;
    private final ObjectMapper mapper;
    public MerchantReconciliationService(MerchantReconciliationStore store, GatewayPaymentClient gateway, MerchantProperties merchant,
            MerchantGatewayProperties gatewayProperties, ReconciliationCsv csv, ObjectMapper mapper) {
        this.store = store; this.gateway = gateway; this.merchant = merchant;
        this.gatewayProperties = gatewayProperties; this.csv = csv; this.mapper = mapper;
    }
    @Transactional(propagation = Propagation.NEVER)
    public ReconciliationResult reconcile(String date) {
        var day = BusinessDay.parse(date);
        var remote = gateway.reconciliation(merchant.id(), day); // HTTP + HMAC first; no database transaction spans this call.
        return compare(day, remote, () -> new ApiException(HttpStatus.BAD_GATEWAY, "INVALID_GATEWAY_RESPONSE",
                "Gateway transaction does not match the stored order binding"));
    }

    @Transactional(propagation = Propagation.NEVER)
    public ReconciliationResult reconcileCsv(String date, byte[] file) {
        var day = BusinessDay.parse(date);
        GatewayReconciliation remote;
        try {
            var report = csv.read(file, gatewayProperties.secretKey());
            remote = GatewayReconciliation.read(report, mapper, merchant.id(), day);
        } catch (IOException | IllegalArgumentException | ArithmeticException ex) {
            throw invalidCsv();
        }
        return compare(day, remote, MerchantReconciliationService::invalidCsv);
    }

    private ReconciliationResult compare(BusinessDay day, GatewayReconciliation remote, Supplier<ApiException> invalidBinding) {
        var snapshot = store.snapshot(day, remote.transactions().stream().map(txn -> txn.txnRef()).toList(),
                remote.transactions().stream().map(txn -> txn.orderId()).toList());
        var byRef = new HashMap<String, OrderDetails>(); var byId = new HashMap<String, OrderDetails>();
        var paid = new LinkedHashMap<String, OrderDetails>();
        long merchantTotal = 0;
        for (var order : snapshot) {
            byId.put(order.orderId(), order);
            if (order.txnRef() != null) byRef.put(order.txnRef(), order);
            if (order.status() == OrderStatus.PAID) {
                if (order.paidAt() == null || order.txnRef() == null || !order.txnRef().matches("TX[0-9]{8}")) throw invalidLocal();
                if (day.contains(order.paidAt().toInstant())) {
                    if (paid.put(order.txnRef(), order) != null) throw invalidLocal();
                    merchantTotal = Math.addExact(merchantTotal, order.amount());
                }
            }
        }
        var missingMerchant = new ArrayList<ReconciliationResult.Difference>();
        var mismatches = new ArrayList<ReconciliationResult.AmountMismatch>();
        var remoteRefs = new HashSet<String>(); long matched = 0;
        for (var txn : remote.transactions()) {
            remoteRefs.add(txn.txnRef());
            var knownRef = byRef.get(txn.txnRef()); var knownId = byId.get(txn.orderId());
            if (knownRef != null && !knownRef.orderId().equals(txn.orderId())
                    || knownId != null && knownId.txnRef() != null && !knownId.txnRef().equals(txn.txnRef())) {
                throw invalidBinding.get();
            }
            var local = paid.get(txn.txnRef());
            if (local == null) missingMerchant.add(new ReconciliationResult.Difference(txn.txnRef(), txn.orderId(), txn.amount()));
            else if (local.amount() != txn.amount()) mismatches.add(new ReconciliationResult.AmountMismatch(txn.txnRef(), txn.orderId(), txn.amount(), local.amount()));
            else matched++;
        }
        var missingGateway = new ArrayList<ReconciliationResult.Difference>();
        for (var order : paid.values()) if (!remoteRefs.contains(order.txnRef())) {
            missingGateway.add(new ReconciliationResult.Difference(order.txnRef(), order.orderId(), order.amount()));
        }
        return new ReconciliationResult(day.date().toString(), matched, List.copyOf(missingMerchant), List.copyOf(missingGateway),
                List.copyOf(mismatches), remote.successAmount(), merchantTotal);
    }
    private static ApiException invalidCsv() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", "CSV signature, contents or order binding is invalid");
    }
    private static ApiException invalidLocal() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_MERCHANT_DATA", "Paid order has an invalid transaction binding or paidAt");
    }
}
