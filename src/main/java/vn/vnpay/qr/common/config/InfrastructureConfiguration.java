package vn.vnpay.qr.common.config;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InfrastructureConfiguration {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // Inject a builder into future HTTP clients; each component owns its client and DTOs.
    @Bean
    RestClient.Builder restClientBuilder() {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictJsonTypes() {
        return builder -> {
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            builder.postConfigurer(mapper -> {
                mapper.coercionConfigFor(LogicalType.Integer)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
                mapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            });
        };
    }
}
