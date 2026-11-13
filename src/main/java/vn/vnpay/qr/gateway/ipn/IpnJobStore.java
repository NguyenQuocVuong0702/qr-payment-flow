package vn.vnpay.qr.gateway.ipn;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IpnJobStore {
    private final IpnOutboxRepository jobs;
    private final IpnLogRepository logs;
    private final IpnProperties properties;
    private final Clock clock;
    private final IpnAuditPayload audit;
    public IpnJobStore(IpnOutboxRepository jobs, IpnLogRepository logs, IpnProperties properties, Clock clock, IpnAuditPayload audit) {
        this.jobs = jobs; this.logs = logs; this.properties = properties; this.clock = clock;
        this.audit = audit;
    }
    @Transactional
    public IpnClaim claimNext() {
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (String ref : jobs.candidates(now, PageRequest.of(0, 20))) {
            var found = jobs.lock(ref);
            if (found.isEmpty()) continue;
            var job = found.get();
            now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (job.abandoned(now)) {
                logs.findByTxnRefAndAttempt(ref, job.getAttemptCount()).orElseThrow()
                        .complete(new IpnDeliveryResult(false, "UNKNOWN_AFTER_CRASH", null, "LEASE_EXPIRED"), now);
                job.finish(false, now.plus(properties.retryDelay(job.getAttemptCount())));
                continue;
            }
            if (!job.ready(now)) continue; // Another worker won after the unlocked candidate query.
            var claim = job.claim(now, now.plus(properties.lease()));
            logs.saveAndFlush(new IpnLog(claim, audit.redact(claim.requestBody()), now));
            return claim;
        }
        return null;
    }
    @Transactional
    public void complete(IpnClaim claim, IpnDeliveryResult result) {
        var job = jobs.lock(claim.txnRef()).orElseThrow();
        if (!job.owns(claim)) return; // Fence a late response from a lease already reclaimed after crash.
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        logs.findByTxnRefAndAttempt(claim.txnRef(), claim.attempt()).orElseThrow().complete(result, now);
        job.finish(result.delivered(), now.plus(properties.retryDelay(claim.attempt())));
    }
}
