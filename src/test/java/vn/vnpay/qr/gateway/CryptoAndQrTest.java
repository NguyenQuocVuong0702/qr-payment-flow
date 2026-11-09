package vn.vnpay.qr.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.qr.Crc16Calculator;
import vn.vnpay.qr.gateway.qr.DynamicQrBuilder;
import static org.assertj.core.api.Assertions.*;

class CryptoAndQrTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HmacSigner hmac = new HmacSigner();

    @Test
    void canonicalizesExactlyAsTheExerciseAndMatchesIndependentHmacFixture() throws Exception {
        var body = mapper.readTree("""
                {"orderId":"ORD20261102001","merchantId":"MC001","expireMinutes":15,
                 "description":"Thanh toan ao thun","amount":55000,"signature":"ignored"}
                """);
        assertThat(hmac.canonicalize(body)).isEqualTo("amount=55000&description=Thanh toan ao thun&expireMinutes=15&merchantId=MC001&orderId=ORD20261102001");
        assertThat(hmac.sign(body, "secret-mc001"))
                .isEqualTo("bdda985548c27afd42ef3b29e6b868b767e1926e54ae52989e197321321e03ff");
        assertThat(hmac.verify(body, "secret-mc001", hmac.sign(body, "secret-mc001"))).isTrue();
        assertThat(hmac.verify(body, "different", hmac.sign(body, "secret-mc001"))).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "xyz", "ABCDEF", "B D", "0000000000000000000000000000000000000000000000000000000000000000"})
    void rejectsMalformedOrWrongSignature(String signature) throws Exception {
        assertThat(hmac.verify(mapper.readTree("{\"amount\":55000}"), "secret", signature)).isFalse();
    }

    @Test
    void preservesSpacesAndDoesNotUrlEncodeCanonicalValues() throws Exception {
        assertThat(hmac.canonicalize(mapper.readTree("{\"b\":\" A B \",\"a\":12,\"c\":null}")))
                .isEqualTo("a=12&b= A B ");
    }

    @Test
    void crcMatchesCcittFalseStandardVector() {
        assertThat(Crc16Calculator.calculate("123456789")).isEqualTo("29B1");
        assertThat(Crc16Calculator.calculate("")).isEqualTo("FFFF");
    }

    @Test
    void tlvUsesTwoDigitCharacterLengthAndRejectsOverflow() {
        assertThat(DynamicQrBuilder.buildTlv("08", "ABC")).isEqualTo("0803ABC");
        assertThat(DynamicQrBuilder.buildTlv("08", "A".repeat(99))).startsWith("0899");
        assertThatThrownBy(() -> DynamicQrBuilder.buildTlv("08", "A".repeat(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamicQrBuilder.buildTlv("8", "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
