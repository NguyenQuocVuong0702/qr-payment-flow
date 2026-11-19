package vn.vnpay.qr.merchant.api;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.common.reconciliation.ReconciliationCsv;
import vn.vnpay.qr.merchant.service.MerchantReconciliationService;

@RestController
public class MerchantReconciliationController {
    private final MerchantReconciliationService service;
    public MerchantReconciliationController(MerchantReconciliationService service) { this.service = service; }
    @PostMapping("/api/merchant/reconcile")
    public ResponseEntity<ReconciliationResult> reconcile(@RequestBody ReconcileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reconcile(request.date()));
    }

    @PostMapping(value = "/api/merchant/reconcile/csv", consumes = "text/csv")
    public ResponseEntity<ReconciliationResult> reconcileCsv(@RequestParam String date, InputStream body) {
        byte[] file;
        try {
            file = body.readNBytes(ReconciliationCsv.MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", "Cannot read reconciliation CSV");
        }
        if (file.length > ReconciliationCsv.MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_TOO_LARGE", "Reconciliation CSV exceeds 4 MiB");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reconcileCsv(date, file));
    }
}
