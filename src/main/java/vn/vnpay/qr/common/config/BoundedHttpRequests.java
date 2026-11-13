package vn.vnpay.qr.common.config;

import java.io.IOException;
import java.net.HttpURLConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Socket read timeout also covers reading the response body; credentials never follow redirects. */
public final class BoundedHttpRequests {
    private BoundedHttpRequests() { }
    public static SimpleClientHttpRequestFactory factory() {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        return factory;
    }
}
