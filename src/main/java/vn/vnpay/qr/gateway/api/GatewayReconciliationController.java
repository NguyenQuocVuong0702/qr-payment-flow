package vn.vnpay.qr.gateway.api;

import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.common.time.BusinessDay;
import vn.vnpay.qr.gateway.service.*;

@RestController
public class GatewayReconciliationController {
    private final PaymentLookupAccess access;
    private final GatewayReconciliationService reconciliation;
    private final ObjectMapper mapper;
    public GatewayReconciliationController(PaymentLookupAccess access, GatewayReconciliationService reconciliation, ObjectMapper mapper) {
        this.access = access; this.reconciliation = reconciliation; this.mapper = mapper;
    }
    @GetMapping("/api/gateway/reconciliation")
    public ReconciliationResponse get(@RequestParam MultiValueMap<String, String> params, @RequestHeader HttpHeaders headers) {
        if (!params.keySet().equals(Set.of("merchantId", "date"))
                || params.values().stream().anyMatch(values -> values.size() != 1 || values.getFirst().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Exactly one merchantId and date are required");
        }
        String merchantId = params.getFirst("merchantId"), date = params.getFirst("date");
        var caller = access.authenticateMerchant(headers, mapper.createObjectNode().put("merchantId", merchantId).put("date", date));
        LookupResponseSigningAdvice.signWith(caller.signingKey());
        return reconciliation.get(caller.merchantId(), BusinessDay.parse(date));
    }
}
