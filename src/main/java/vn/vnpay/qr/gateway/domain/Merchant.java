package vn.vnpay.qr.gateway.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "merchant")
public class Merchant {
    @Id
    @Column(name = "merchant_id", length = 13, nullable = false)
    private String merchantId;

    @Column(name = "name", length = 25, nullable = false)
    private String name;

    @Column(name = "city", length = 15, nullable = false)
    private String city;

    @Column(name = "mcc", length = 4, nullable = false)
    private String mcc;

    @Column(name = "secret_key", length = 128, nullable = false)
    private String secretKey;

    @Column(name = "ipn_url", length = 255, nullable = false)
    private String ipnUrl;

    @Column(name = "account_no", length = 32, nullable = false)
    private String accountNo;

    protected Merchant() { }

    public String getMerchantId() { return merchantId; }
    public String getName() { return name; }
    public String getCity() { return city; }
    public String getMcc() { return mcc; }
    public String getSecretKey() { return secretKey; }
    public String getIpnUrl() { return ipnUrl; }
    public String getAccountNo() { return accountNo; }
}
