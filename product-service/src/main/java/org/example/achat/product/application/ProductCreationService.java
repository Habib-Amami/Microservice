package org.example.achat.product.application;

import feign.FeignException;
import org.example.achat.product.api.dto.ProductCreateRequest;
import org.example.achat.product.api.dto.ProductRequest;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.infrastructure.inventory.InventoryClient;
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
 */
@Service
public class ProductCreationService {

    private static final Logger log = LoggerFactory.getLogger(ProductCreationService.class);

    private final ProductApplicationService products;
    private final InventoryClient inventory;

    public ProductCreationService(ProductApplicationService products, InventoryClient inventory) {
        this.products = products;
        this.inventory = inventory;
    }

    public ProductResponse create(ProductCreateRequest request) {
        validateStock(request.stockId());

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
        } catch (FeignException exception) {
            // Never leave a stockless product behind. If Inventory committed
            // but the response was lost, this leaves a stale assignment row,
            // which Inventory already tolerates when deleting a stock.
            log.warn("Stock assignment failed for product {}; rolling back the catalog row",
                    created.idProduit(), exception);
            products.delete(created.idProduit());
            throw new InventoryServiceUnavailableException(
                    "Could not assign the product to stock " + request.stockId());
        }
    }

    private void validateStock(Long stockId) {
        try {
            inventory.getStock(stockId);
        } catch (FeignException.NotFound exception) {
            throw new StockNotFoundException(stockId);
        } catch (FeignException exception) {
            throw new InventoryServiceUnavailableException(
                    "Could not validate stock " + stockId + " with inventory-service");
        }
    }
}
