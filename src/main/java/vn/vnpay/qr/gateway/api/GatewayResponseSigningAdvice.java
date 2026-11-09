package vn.vnpay.qr.gateway.api;

import java.lang.reflect.Type;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import vn.vnpay.qr.common.crypto.HmacSigner;
import vn.vnpay.qr.gateway.repository.MerchantRepository;

/** Sign JSON success/error responses when a parsed request identifies a registered merchant. */
@ControllerAdvice
public class GatewayResponseSigningAdvice extends RequestBodyAdviceAdapter implements ResponseBodyAdvice<Object> {
    private static final String MERCHANT = GatewayResponseSigningAdvice.class.getName() + ".merchant";
    private final MerchantRepository merchants;
    private final ObjectMapper mapper;
    private final HmacSigner hmac;

    public GatewayResponseSigningAdvice(MerchantRepository merchants, ObjectMapper mapper, HmacSigner hmac) {
        this.merchants = merchants;
        this.mapper = mapper;
        this.hmac = hmac;
    }

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CreatePaymentRequest.class;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage message, MethodParameter parameter,
            Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        var request = (CreatePaymentRequest) body;
        RequestContextHolder.currentRequestAttributes().setAttribute(MERCHANT,
                request.merchantId() == null ? "" : request.merchantId(), RequestAttributes.SCOPE_REQUEST);
        return body;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && attributes.getAttribute(MERCHANT, RequestAttributes.SCOPE_REQUEST) instanceof String id
                && body != null) {
            merchants.findById(id).ifPresent(merchant -> response.getHeaders().set("X-Signature",
                    hmac.sign(mapper.valueToTree(body), merchant.getSecretKey())));
        }
        return body;
    }
}
