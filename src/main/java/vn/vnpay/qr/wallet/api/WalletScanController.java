package vn.vnpay.qr.wallet.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpay.qr.wallet.service.WalletScanService;

@RestController
public class WalletScanController {
    private final WalletScanService scans;

    public WalletScanController(WalletScanService scans) { this.scans = scans; }

    @PostMapping("/api/wallet/scan")
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) { return scans.scan(request.qrString()); }
}
