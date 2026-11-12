package vn.vnpay.qr.wallet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.common.qr.Crc16;
import vn.vnpay.qr.wallet.qr.PaymentQrParser;
import static org.assertj.core.api.Assertions.*;

class PaymentQrParserTest {
    private final PaymentQrParser parser = new PaymentQrParser();

    @Test
    void acceptsB2FixtureAndSplitsAfterExactlyTwelveCharacters() {
        var parsed = parser.parse("00020101021226230010A0000007750105MC0015204549953037045405550005802VN5908SHOP ABC6005HANOI62530522012611020915TX000000010818Thanh toan ao thun0901M6304C9E9");
        assertThat(parsed.txnRef()).isEqualTo("TX00000001");
        assertThat(parsed.amount()).isEqualTo(55000);
        assertThat(Crc16.calculate("123456789")).isEqualTo("29B1");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "0", "0002", "00AA01", "０００２０１", "0002010", "0002019900", "000201000201"})
    void rejectsMalformedInput(String qr) { invalid(qr); }

    @ParameterizedTest
    @ValueSource(strings = {"00", "01", "26", "52", "53", "54", "58", "59", "60", "62", "63"})
    void rejectsEveryMissingRequiredTag(String tag) {
        var fields = fields();
        fields.remove(tag);
        invalid(encode(fields));
    }

    @ParameterizedTest
    @MethodSource("invalidReferences")
    void rejectsBoundaryAndDateErrorsInReference(String reference) {
        var fields = fields();
        fields.put("62", tlv("05", reference) + tlv("08", "Ao thun") + tlv("09", "M"));
        invalid(encode(fields));
    }

    static Stream<String> invalidReferences() {
        return Stream.of("0", "01", "012611020915", "022611020915TX00000001", "012611020915TX0000000",
                "012611020915TX000000001", "012611020915TX00000001X", "012611020915../private",
                "012613020915TX00000001", "012602290915TX00000001", "012611022400TX00000001",
                "012611020960TX00000001", "01abcdefghijTX00000001", "012611020915tx00000001");
    }

    @Test
    void acceptsLeapDayAndDoesNotUseQrTimestampToDecideExpiry() {
        var fields = fields();
        fields.put("62", tlv("05", "012402290000TX00000001") + tlv("08", "Ao thun") + tlv("09", "M"));
        assertThat(parser.parse(encode(fields)).txnRef()).isEqualTo("TX00000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"26/00", "26/01", "62/05", "62/08", "62/09"})
    void rejectsMissingNestedTag(String path) {
        var fields = fields();
        if (path.startsWith("26")) {
            fields.put("26", path.endsWith("00") ? tlv("01", "MC001") : tlv("00", "A000000775"));
        } else {
            String nested = (path.endsWith("05") ? "" : tlv("05", "012611020915TX00000001"))
                    + (path.endsWith("08") ? "" : tlv("08", "Ao thun"))
                    + (path.endsWith("09") ? "" : tlv("09", "M"));
            fields.put("62", nested);
        }
        invalid(encode(fields));
    }

    @Test
    void rejectsDuplicatesTruncationNonFinalCrcAndInvalidUnicode() {
        String qr = encode(fields());
        invalid("000201" + qr);
        invalid(qr.substring(0, qr.length() - 1));
        invalid(qr + "9901X");
        invalid(qr.replace("MC001", "MC\uD8001"));
        var fields = fields();
        fields.put("26", tlv("00", "A000000775") + tlv("01", "MC001") + tlv("01", "MC001"));
        invalid(encode(fields));
        fields = fields();
        fields.put("62", "05AAinvalid");
        invalid(encode(fields));
        invalid("X".repeat(513));
    }

    @Test
    void reportsCrcMismatchWhenOneValidNameCharacterChanges() {
        String qr = encode(fields());
        assertThatThrownBy(() -> parser.parse(qr.replace("SHOP ABC", "SHOP ABD")))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.errorCode()).isEqualTo("CRC_MISMATCH"));
    }

    @Test
    void recomputedAmountTamperingPassesParsingForGatewayComparison() {
        var fields = fields();
        fields.put("54", "5000");
        assertThat(parser.parse(encode(fields)).amount()).isEqualTo(5000);
    }

    private void invalid(String value) {
        assertThatThrownBy(() -> parser.parse(value)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo("INVALID_QR"));
    }

    static LinkedHashMap<String, String> fields() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("00", "01"); fields.put("01", "12");
        fields.put("26", tlv("00", "A000000775") + tlv("01", "MC001"));
        fields.put("52", "5499"); fields.put("53", "704"); fields.put("54", "55000");
        fields.put("58", "VN"); fields.put("59", "SHOP ABC"); fields.put("60", "HANOI");
        fields.put("62", tlv("05", "012611020915TX00000001") + tlv("08", "Ao thun") + tlv("09", "M"));
        fields.put("63", "placeholder");
        return fields;
    }

    static String encode(Map<String, String> fields) {
        var payload = new StringBuilder();
        fields.forEach((tag, value) -> { if (!tag.equals("63")) payload.append(tlv(tag, value)); });
        if (!fields.containsKey("63")) return payload.toString();
        payload.append("6304");
        return payload + Crc16.calculate(payload.toString());
    }

    static String tlv(String tag, String value) { return tag + "%02d".formatted(value.codePointCount(0, value.length())) + value; }
}
