-- Product-service sends an Idempotency-Key with every stock assignment so a
-- Resilience4j retry of a lost response cannot apply the same write twice.
-- The key is stored atomically with the assignment, and the unique constraint
-- is the last line of defence against concurrent duplicates.
CREATE TABLE assignment_idempotency_keys (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    stock_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_assignment_idempotency_key UNIQUE (idempotency_key)
) ENGINE=InnoDB;
