package vn.vnpay.qr.gateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import vn.vnpay.qr.common.crypto.HmacSigner;

@ControllerAdvice
public class LookupResponseSigningAdvice implements ResponseBodyAdvice<Object> {
    private static final String KEY = LookupResponseSigningAdvice.class.getName() + ".key";
    private final HmacSigner hmac;
    private final ObjectMapper mapper;

    public LookupResponseSigningAdvice(HmacSigner hmac, ObjectMapper mapper) {
        this.hmac = hmac;
        this.mapper = mapper;
    }

    public static void signWith(String key) {
        RequestContextHolder.currentRequestAttributes().setAttribute(KEY, key, RequestAttributes.SCOPE_REQUEST);
    }

    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) { return true; }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType mediaType,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (body != null && attributes != null
                && attributes.getAttribute(KEY, RequestAttributes.SCOPE_REQUEST) instanceof String key) {
            response.getHeaders().set("X-Signature", hmac.sign(mapper.valueToTree(body), key));
            response.getHeaders().set("Cache-Control", "no-store");
        }
        return body;
    }
}
