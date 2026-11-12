package vn.vnpay.qr.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.gateway.config.WalletReadProperties;
import vn.vnpay.qr.gateway.repository.MerchantRepository;

@Service
public class PaymentLookupAccess {
    private final MerchantRepository merchants;
    private final WalletReadProperties wallet;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;

    public PaymentLookupAccess(MerchantRepository merchants, WalletReadProperties wallet, ObjectMapper mapper, HmacSigner hmac) {
        this.merchants = merchants;
        this.wallet = wallet;
        this.mapper = mapper;
        this.hmac = hmac;
    }

    public LookupCaller authenticate(HttpHeaders headers, String txnRef) {
        // Presence of either Merchant header always selects the HMAC branch. Never fall back to Wallet.
        if (headers.containsKey("X-Merchant-Id") || headers.containsKey("X-Signature")) {
            if (headers.containsKey(HttpHeaders.AUTHORIZATION) || !single(headers, "X-Merchant-Id")
                    || !single(headers, "X-Signature")) throw invalidSignature();
            String id = headers.getFirst("X-Merchant-Id");
            var merchant = merchants.findById(id).orElseThrow(PaymentLookupAccess::invalidSignature);
            var fields = mapper.createObjectNode().put("merchantId", id).put("txnRef", txnRef);
            if (!hmac.verify(fields, merchant.getSecretKey(), headers.getFirst("X-Signature"))) throw invalidSignature();
            return new LookupCaller(false, id, merchant.getSecretKey());
        }
        String expected = "Bearer " + wallet.readToken();
        if (!single(headers, HttpHeaders.AUTHORIZATION) || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                headers.getFirst(HttpHeaders.AUTHORIZATION).getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WALLET_CREDENTIALS", "Wallet read credentials are required");
        }
        return new LookupCaller(true, null, wallet.readToken());
    }

    private boolean single(HttpHeaders headers, String name) {
        var values = headers.get(name);
        return values != null && values.size() == 1 && !values.getFirst().isBlank();
    }

    private static ApiException invalidSignature() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid merchant lookup signature");
    }
}
