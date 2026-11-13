package vn.vnpay.qr.merchant.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.merchant.service.MerchantReconciliationService;

@RestController
public class MerchantReconciliationController {
    private final MerchantReconciliationService service;
    public MerchantReconciliationController(MerchantReconciliationService service) { this.service = service; }
    @PostMapping("/api/merchant/reconcile")
    public ResponseEntity<ReconciliationResult> reconcile(@RequestBody ReconcileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reconcile(request.date()));
    }
}
