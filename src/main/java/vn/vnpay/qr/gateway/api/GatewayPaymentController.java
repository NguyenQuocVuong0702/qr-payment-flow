package vn.vnpay.qr.gateway.api;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import vn.vnpay.qr.gateway.service.GatewayPaymentService;

@RestController
@RequestMapping("/api/gateway/payments")
public class GatewayPaymentController {
    private final GatewayPaymentService payments;

    public GatewayPaymentController(GatewayPaymentService payments) { this.payments = payments; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePaymentResponse create(@Valid @RequestBody CreatePaymentRequest request) {
        return payments.create(request);
    }
}
