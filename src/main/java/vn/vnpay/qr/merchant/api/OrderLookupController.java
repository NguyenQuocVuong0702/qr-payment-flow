package vn.vnpay.qr.merchant.api;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.merchant.service.MerchantOrderLookupService;
import vn.vnpay.qr.merchant.service.MerchantOrderLookupStore;

@RestController
@RequestMapping("/api/merchant/orders/{orderId}")
public class OrderLookupController {
    private final MerchantOrderLookupStore store;
    private final MerchantOrderLookupService service;
    public OrderLookupController(MerchantOrderLookupStore store, MerchantOrderLookupService service) {
        this.store = store; this.service = service;
    }
    @GetMapping
    public ResponseEntity<OrderDetails> get(@PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.load(orderId));
    }
    @PostMapping("/sync")
    public ResponseEntity<OrderDetails> sync(@PathVariable String orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.sync(orderId));
    }
}
