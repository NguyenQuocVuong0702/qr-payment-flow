-- Insert only when absent; never overwrite a registry entry during restart.
MERGE INTO merchant target
USING (VALUES ('MC001', 'SHOP ABC', 'HANOI', '5499', 'secret-mc001',
       'http://localhost:8080/api/merchant/ipn', '9000001'))
       source(merchant_id, name, city, mcc, secret_key, ipn_url, account_no)
ON target.merchant_id = source.merchant_id
WHEN NOT MATCHED THEN INSERT (merchant_id, name, city, mcc, secret_key, ipn_url, account_no)
VALUES (source.merchant_id, source.name, source.city, source.mcc, source.secret_key, source.ipn_url, source.account_no);
