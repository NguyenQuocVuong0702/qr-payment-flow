package vn.vnpay.qr.gateway;

import java.io.File;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Real processes and persistent H2, without a mocked Bank or a Spring test context in the child. */
class RestartPersistenceIT {
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Process> processes=new ArrayList<>();
    private String base;

    @Test
    void committedPaymentIdempotencyBalancesAndPinLockSurviveAbruptRestart() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("target"),"restart-b5-").toAbsolutePath();
        String url="jdbc:h2:file:"+directory.resolve("payments").toString().replace('\\','/');
        ObjectNode firstRequest=null;
        try {
            Process first=start(url,directory.resolve("first.log"));
            JsonNode payment=createPayment(55000);
            firstRequest=paymentRequest(payment.path("txnRef").asText(),55000,"U001","123456");
            String firstResult=post("/api/wallet/payments",firstRequest,200);
            String firstBank=mapper.readTree(firstResult).path("bankRef").asText();
            assertThat(mapper.readTree(firstResult).path("status").asText()).isEqualTo("SUCCESS");
            for(int attempt=0;attempt<3;attempt++) {
                post("/api/wallet/payments",paymentRequest(payment.path("txnRef").asText(),55000,"U002","000000"),attempt<2?401:423);
            }
            kill(first); // Deliberately no graceful shutdown or H2 SHUTDOWN command.
            Process second=start(url,directory.resolve("second.log"));
            post("/api/wallet/payments",paymentRequest(payment.path("txnRef").asText(),55000,"U002","123456"),423);
            assertThat(post("/api/wallet/payments",firstRequest,200)).isEqualTo(firstResult);
            JsonNode next=createPayment(1000);
            JsonNode nextResult=mapper.readTree(post("/api/wallet/payments",paymentRequest(next.path("txnRef").asText(),1000,"U001","123456"),200));
            assertThat(next.path("txnRef").asText()).isNotEqualTo(payment.path("txnRef").asText());
            assertThat(nextResult.path("bankRef").asText()).isNotEqualTo(firstBank);
            kill(second);
            // Independent JDBC connection after both processes exit verifies actual disk state.
            try(var db=DriverManager.getConnection(url,"sa","");var sql=db.createStatement()) {
                try(var rows=sql.executeQuery("SELECT account_no,balance FROM account ORDER BY account_no")) {
                    var balances=new LinkedHashMap<String,Long>();while(rows.next())balances.put(rows.getString(1),rows.getLong(2));
                    assertThat(balances).containsExactlyInAnyOrderEntriesOf(Map.of("1000001",944000L,"1000002",10000L,"9000001",56000L));
                }
                assertScalar(sql,"SELECT SUM(balance) FROM account",1010000);
                assertScalar(sql,"SELECT COUNT(*) FROM ledger_entry",4);
                assertScalar(sql,"SELECT COUNT(DISTINCT bank_ref) FROM ledger_entry",2);
                assertScalar(sql,"SELECT COUNT(*) FROM idempotency",2);
                assertScalar(sql,"SELECT COUNT(*) FROM orders WHERE status='WAITING_PAYMENT'",2);
                assertScalar(sql,"SELECT failed_pin_attempts FROM wallet_user WHERE user_id='U002'",3);
                assertScalar(sql,"SELECT COUNT(*) FROM wallet_user WHERE pin_hash='123456'",0);
                assertScalar(sql,"SELECT COUNT(*) FROM (SELECT bank_ref FROM ledger_entry GROUP BY bank_ref HAVING COUNT(*)<>2 OR SUM(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END)<>0)",0);
            }
        } finally { for(Process process:processes) if(process.isAlive())kill(process); }
    }

    private Process start(String url,Path log) throws Exception {
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        base="http://127.0.0.1:"+port;
        String cp=Arrays.stream(System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(Pattern.quote(File.pathSeparator)))
                .filter(entry->!entry.replace('\\','/').endsWith("/test-classes")).collect(Collectors.joining(File.pathSeparator));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
        var builder=new ProcessBuilder(java.toString(),"-cp",cp,"vn.vnpay.qr.QrPaymentApplication",
                "--server.port="+port,"--spring.datasource.url="+url+";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE")
                .redirectErrorStream(true).redirectOutput(log.toFile());
        // Keep the verification independent of a developer's external Gateway URL or DB password.
        builder.environment().remove("QR_GATEWAY_BASE_URL");builder.environment().remove("QR_DB_PASSWORD");
        builder.command().add("--spring.datasource.password=");
        Process process=builder.start(); processes.add(process);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<deadline) {
            assertThat(process.isAlive()).as("Application startup log: %s",log).isTrue();
            try {
                var response=http.send(HttpRequest.newBuilder(URI.create(base+"/api/merchant/orders")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()==405)return process;
            } catch(java.io.IOException ex) { /* Startup has not bound the HTTP port yet. */ }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Startup timeout; see "+log);
    }
    private JsonNode createPayment(long amount)throws Exception {
        var order=mapper.readTree(post("/api/merchant/orders",mapper.createObjectNode().put("amount",amount).put("description","Ao thun"),201));
        JsonNode payment=mapper.readTree(post("/api/merchant/orders/"+order.path("orderId").asText()+"/payments",mapper.createObjectNode().put("expireMinutes",60),201));
        post("/api/wallet/scan",mapper.createObjectNode().put("userId","U001").put("qrString",payment.path("qrString").asText()),200);
        return payment;
    }
    private ObjectNode paymentRequest(String ref,long amount,String user,String pin) {
        return mapper.createObjectNode().put("userId",user).put("txnRef",ref).put("amount",amount).put("pin",pin).put("requestId",UUID.randomUUID().toString());
    }
    private String post(String path,JsonNode body,int expected)throws Exception {
        var response=http.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s response: %s",path,response.body()).isEqualTo(expected);
        return response.body();
    }
    private void assertScalar(java.sql.Statement sql,String query,long expected)throws Exception {
        try(var rows=sql.executeQuery(query)){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(expected);}
    }
    private void kill(Process process)throws Exception {process.destroyForcibly();assertThat(process.waitFor(15,TimeUnit.SECONDS)).isTrue();}
}
