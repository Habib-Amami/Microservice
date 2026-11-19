package org.example.achat.product.application;

import org.example.achat.product.api.dto.ProductCreateRequest;
import org.example.achat.product.api.dto.ProductRequest;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.infrastructure.inventory.InventoryResilienceGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates a product together with its mandatory stock assignment.
 *
 * <p>Product-service owns the catalog row; inventory-service owns the
 * assignment. The stock is checked first so an unknown or unavailable stock
 * never leaves a new product behind. The catalog row is committed before it is
 * handed to Inventory, which validates it via {@code GET /produits/{id}} while
 * registering the assignment.
 *
 * <p>Every inventory call goes through {@link InventoryResilienceGateway},
 * which applies the configured Resilience4j retry, rate limiter, circuit
 * breaker and fallback and translates failures into the domain exceptions
 * handled by {@code ProductExceptionHandler}.
 */
@Service
public class ProductCreationService {

    private static final Logger log = LoggerFactory.getLogger(ProductCreationService.class);

    private final ProductApplicationService products;
    private final InventoryResilienceGateway inventory;

    public ProductCreationService(ProductApplicationService products,
                                  InventoryResilienceGateway inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    public ProductResponse create(ProductCreateRequest request) {
        // Throws StockNotFoundException / InventoryServiceUnavailableException
        // from the gateway before any catalog row is written.
        inventory.getStock(request.stockId());

        // Called through the transactional proxy, so the row is committed
        // before Inventory looks the product up.
        ProductResponse created = products.create(new ProductRequest(
                request.codeProduit(),
                request.libelleProduit(),
                request.prix(),
                request.dateCreation(),
                request.dateDerniereModification(),
                request.categorieProduitId()));

        try {
            // Inventory saves the assignment and pushes stockId back into this
            // service's read projection.
            return inventory.assignStock(created.idProduit(), request.stockId());
        } catch (InventoryServiceUnavailableException | StockNotFoundException exception) {
            // Never leave a stockless product behind. If Inventory committed
            // but the response was lost, this leaves a stale assignment row,
            // which Inventory already tolerates when deleting a stock.
            log.warn("Stock assignment failed for product {}; rolling back the catalog row",
                    created.idProduit(), exception);
            products.delete(created.idProduit());
            throw exception;
        }
    }
}
