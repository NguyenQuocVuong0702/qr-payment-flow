package vn.vnpay.qr.gateway.qr;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class Crc16Calculator {
    private Crc16Calculator() { }

    public static String calculate(String payload) {
        int crc = 0xFFFF;
        for (byte value : payload.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (value & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = ((crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xFFFF;
            }
        }
        return String.format(Locale.ROOT, "%04X", crc);
    }
}
