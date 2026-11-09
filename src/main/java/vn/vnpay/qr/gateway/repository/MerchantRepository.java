package vn.vnpay.qr.gateway.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpay.qr.gateway.domain.Merchant;

public interface MerchantRepository extends JpaRepository<Merchant, String> {
}
