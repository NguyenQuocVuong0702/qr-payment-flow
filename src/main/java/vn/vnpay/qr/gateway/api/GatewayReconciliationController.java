package vn.vnpay.qr.gateway.api;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.common.reconciliation.ReconciliationCsv;
import vn.vnpay.qr.common.time.BusinessDay;
import vn.vnpay.qr.gateway.service.*;

@RestController
public class GatewayReconciliationController {
    private final PaymentLookupAccess access;
    private final GatewayReconciliationService reconciliation;
    private final ObjectMapper mapper;
    private final ReconciliationCsv csv;
    public GatewayReconciliationController(PaymentLookupAccess access, GatewayReconciliationService reconciliation,
            ObjectMapper mapper, ReconciliationCsv csv) {
        this.access = access; this.reconciliation = reconciliation; this.mapper = mapper;
        this.csv = csv;
    }
    @GetMapping("/api/gateway/reconciliation")
    public ReconciliationResponse get(@RequestParam MultiValueMap<String, String> params, @RequestHeader HttpHeaders headers) {
        var caller = authenticate(params, headers);
        LookupResponseSigningAdvice.signWith(caller.signingKey());
        return reconciliation.get(caller.merchantId(), BusinessDay.parse(params.getFirst("date")));
    }

    @GetMapping("/api/gateway/reconciliation/csv")
    public ResponseEntity<byte[]> download(@RequestParam MultiValueMap<String, String> params, @RequestHeader HttpHeaders headers) {
        var caller = authenticate(params, headers);
        var day = BusinessDay.parse(params.getFirst("date"));
        var report = reconciliation.get(caller.merchantId(), day);
        byte[] file = csv.write(mapper.valueToTree(report), caller.signingKey());
        if (file.length > ReconciliationCsv.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_TOO_LARGE", "Reconciliation CSV exceeds 4 MiB");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("reconciliation-" + day.date() + ".csv").build().toString())
                .body(file);
    }

    private LookupCaller authenticate(MultiValueMap<String, String> params, HttpHeaders headers) {
        if (!params.keySet().equals(Set.of("merchantId", "date"))
                || params.values().stream().anyMatch(values -> values.size() != 1 || values.getFirst().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Exactly one merchantId and date are required");
        }
        String merchantId = params.getFirst("merchantId"), date = params.getFirst("date");
        return access.authenticateMerchant(headers, mapper.createObjectNode().put("merchantId", merchantId).put("date", date));
    }
}
