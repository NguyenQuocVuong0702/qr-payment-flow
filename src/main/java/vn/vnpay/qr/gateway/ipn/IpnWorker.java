package vn.vnpay.qr.gateway.ipn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class IpnWorker {
    private final IpnJobStore store;
    private final IpnHttpClient http;
    public IpnWorker(IpnJobStore store, IpnHttpClient http) { this.store = store; this.http = http; }
    @Transactional(propagation = Propagation.NEVER)
    public boolean runOnce() {
        var claim = store.claimNext();
        if (claim == null) return false;
        var result = http.send(claim); // Claim transaction has committed; no DB transaction spans HTTP.
        store.complete(claim, result);
        return true;
    }
}
