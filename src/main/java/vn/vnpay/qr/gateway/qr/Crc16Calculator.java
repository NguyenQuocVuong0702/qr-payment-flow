package vn.vnpay.qr.gateway.qr;

public final class Crc16Calculator {
    private Crc16Calculator() { }

    public static String calculate(String payload) {
        return vn.vnpay.qr.common.qr.Crc16.calculate(payload);
    }
}
