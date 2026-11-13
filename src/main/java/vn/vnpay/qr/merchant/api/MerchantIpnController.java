package vn.vnpay.qr.merchant.api;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.merchant.service.MerchantIpnService;

@RestController
public class MerchantIpnController {
    private final MerchantIpnService service;
    public MerchantIpnController(MerchantIpnService service) { this.service = service; }
    @PostMapping("/api/merchant/ipn")
    public IpnResponse receive(@RequestBody IpnRequest request) { return service.receive(request); }
}
