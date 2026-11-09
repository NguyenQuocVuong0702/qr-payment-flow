package vn.vnpay.qr.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public class HmacSigner {
    public String canonicalize(JsonNode body) {
        if (!body.isObject()) {
            throw new IllegalArgumentException("Signed body must be an object");
        }
        var fields = new TreeMap<String, String>();
        body.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("signature") && !entry.getValue().isNull()) {
                if (entry.getValue().isContainerNode()) {
                    throw new IllegalArgumentException("Only scalar signature fields are supported in B2");
                }
                fields.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return fields.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    public String sign(JsonNode body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonicalize(body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    public boolean verify(JsonNode body, String secret, String signature) {
        if (signature == null || !signature.matches("[0-9a-f]{64}")) {
            return false;
        }
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(body, secret)),
                HexFormat.of().parseHex(signature));
    }
}
