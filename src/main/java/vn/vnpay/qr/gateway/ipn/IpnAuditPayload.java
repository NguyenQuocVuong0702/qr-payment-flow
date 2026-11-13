package vn.vnpay.qr.gateway.ipn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Audit only: never mutate the signed delivery snapshot or financial rows. */
@Component
public class IpnAuditPayload implements ApplicationRunner {
    private final ObjectMapper mapper;
    private final IpnLogRepository logs;
    public IpnAuditPayload(ObjectMapper mapper, IpnLogRepository logs) { this.mapper = mapper; this.logs = logs; }
    public String redact(String body) {
        try {
            if (!(mapper.readTree(body) instanceof ObjectNode json)) throw new IllegalStateException("Invalid stored IPN audit payload");
            json.remove("signature");
            return mapper.writeValueAsString(json);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot redact IPN audit payload"); }
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        // Also remove signatures from existing B6 audit history during an upgrade. Keep all IDs/outcomes/timestamps.
        for (var log : logs.findAll()) {
            String redacted = redact(log.getRequestBody());
            if (!redacted.equals(log.getRequestBody())) logs.redactBody(log.getId(), redacted);
        }
        // Column-only update preserves a concurrent worker's ACK/outcome, even when its log was read earlier.
    }
}
