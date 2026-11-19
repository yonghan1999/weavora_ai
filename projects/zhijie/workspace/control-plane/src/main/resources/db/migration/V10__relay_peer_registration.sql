-- 强制中继只登记公钥资格，不存储或下发直连端点。
ALTER TABLE network_peer_endpoints ALTER COLUMN endpoint DROP NOT NULL;
