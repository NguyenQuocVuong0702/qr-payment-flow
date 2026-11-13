package vn.vnpay.qr.gateway;

import java.time.Instant;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.common.time.BusinessDay;
import static org.assertj.core.api.Assertions.*;

class ReconciliationHmacTest {
    final ObjectMapper mapper=new ObjectMapper();
    final HmacSigner hmac=new HmacSigner();
    @Test void nestedResponseMatchesIndependentPythonHmacVector() throws Exception {
        var body=mapper.readTree("""
                {"merchantId":"MC001","date":"2026-11-02","successCount":2,"successAmount":110000,"transactions":[
                {"txnRef":"TX00000001","orderId":"ORD20261102001","amount":55000,"status":"SUCCESS","bankRef":"BK20261102000001","paidAt":"2026-11-02T09:03:12+07:00"},
                {"txnRef":"TX00000003","orderId":"ORD20261102003","amount":55000,"status":"SUCCESS","bankRef":"BK20261102000002","paidAt":"2026-11-02T10:20:00+07:00"}]}
                """);
        assertThat(hmac.sign(body,"secret-mc001")).isEqualTo("c845704f632ff84a0a7212650a2dbd782ac7ccd0d21204270d372c5a628d5fdd");
        assertThat(hmac.canonicalize(body)).startsWith("date=2026-11-02&merchantId=MC001&successAmount=110000&successCount=2&transactions=[{\"amount\":55000,\"bankRef\":");
        var array=(ArrayNode)body.path("transactions");var first=array.get(0);array.set(0,array.get(1));array.set(1,first);
        assertThat(hmac.sign(body,"secret-mc001")).isNotEqualTo("c845704f632ff84a0a7212650a2dbd782ac7ccd0d21204270d372c5a628d5fdd");
    }
    @Test void requestVectorAndExistingScalarRulesRemainUnchanged() throws Exception {
        assertThat(hmac.sign(mapper.readTree("{\"merchantId\":\"MC001\",\"date\":\"2026-11-02\"}"),"secret-mc001"))
                .isEqualTo("3e01f48ad5777cf5f12725406029a2ae37936d4e070afb30e12e3d692fe5911f");
        assertThat(hmac.canonicalize(mapper.readTree("{\"z\":null,\"signature\":\"ignored\",\"b\":\" A B \",\"a\":12}"))).isEqualTo("a=12&b= A B ");
    }
    @Test void sortsNestedObjectsRecursivelyPreservesArraysAndEscapesJson() throws Exception {
        var left=mapper.readTree("{\"x\":{\"z\":null,\"b\":[{\"z\":2,\"a\":\"Việt \\\"QR\\\"\"}],\"a\":1}}");
        var right=mapper.readTree("{\"x\":{\"a\":1,\"b\":[{\"a\":\"Việt \\\"QR\\\"\",\"z\":2}],\"z\":null}}");
        assertThat(hmac.canonicalize(left)).isEqualTo("x={\"a\":1,\"b\":[{\"a\":\"Việt \\\"QR\\\"\",\"z\":2}],\"z\":null}");
        assertThat(hmac.sign(left,"secret")).isEqualTo(hmac.sign(right,"secret"));
    }
    @Test void businessDayUsesVietnamHalfOpenBoundsWithoutDependingOnJvmTimezone() {
        var day=BusinessDay.parse("2026-11-13");
        assertThat(day.start()).isEqualTo(Instant.parse("2026-11-12T17:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-11-13T17:00:00Z"));
        assertThat(day.contains(day.start())).isTrue();assertThat(day.contains(day.end())).isFalse();
    }
}
