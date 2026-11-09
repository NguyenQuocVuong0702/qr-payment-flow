package vn.vnpay.qr.gateway.qr;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;
import vn.vnpay.qr.gateway.domain.Merchant;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Component
public class DynamicQrBuilder {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("yyMMddHHmm")
            .withZone(BUSINESS_ZONE);

    public static String buildTlv(String tag, String value) {
        int length = value.codePointCount(0, value.length());
        if (!tag.matches("[0-9]{2}") || length > 99) {
            throw new IllegalArgumentException("TLV requires a two-digit tag and length <= 99");
        }
        return tag + String.format(Locale.ROOT, "%02d", length) + value;
    }

    public String build(Merchant merchant, String txnRef, long amount, String description, Instant expireAt) {
        String account = buildTlv("00", "A000000775") + buildTlv("01", merchant.getMerchantId());
        String additional = buildTlv("05", "01" + EXPIRY.format(expireAt) + txnRef)
                + buildTlv("08", description) + buildTlv("09", "M");
        String payload = buildTlv("00", "01") + buildTlv("01", "12") + buildTlv("26", account)
                + buildTlv("52", merchant.getMcc()) + buildTlv("53", "704")
                + buildTlv("54", Long.toString(amount)) + buildTlv("58", "VN")
                + buildTlv("59", merchant.getName()) + buildTlv("60", merchant.getCity())
                + buildTlv("62", additional) + "6304";
        return payload + Crc16Calculator.calculate(payload);
    }
}
