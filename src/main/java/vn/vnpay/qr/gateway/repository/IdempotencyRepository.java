package vn.vnpay.qr.gateway.repository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.gateway.domain.IdempotencyRecord;
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> { }
