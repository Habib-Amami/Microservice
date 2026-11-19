-- Read projection of the stock assignment owned by inventory-service.
-- The source of truth remains inventory_db.stock_product_assignments;
-- product-service stores the id so product reads stay local and do not
-- require a remote call (or create a dependency cycle).
ALTER TABLE products ADD COLUMN stock_id BIGINT NULL;
