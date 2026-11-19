package org.example.achat.product.application;

import org.example.achat.product.api.dto.ProductCreateRequest;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.infrastructure.inventory.InventoryResilienceGateway;
import org.example.achat.product.infrastructure.inventory.dto.StockResponse;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductCreationServiceTest {

    private final ProductApplicationService products = mock(ProductApplicationService.class);
    private final InventoryResilienceGateway inventory = mock(InventoryResilienceGateway.class);
    private final ProductCreationService service = new ProductCreationService(products, inventory);

    private static ProductCreateRequest request(Long stockId) {
        return new ProductCreateRequest("P-1", "Pen", 1.5f, null, null, null, stockId);
    }

    @Test
    void validatesStockCreatesProductThenRegistersAssignment() {
        ProductResponse created = new ProductResponse(8L, "P-1", "Pen", 1.5f, null, null);
        ProductResponse assigned = new ProductResponse(8L, "P-1", "Pen", 1.5f, 3L, null);
        when(inventory.getStock(3L)).thenReturn(new StockResponse(3L, "Main warehouse", 10, 2));
        when(products.create(any())).thenReturn(created);
        when(inventory.assignStock(8L, 3L)).thenReturn(assigned);

        ProductResponse result = service.create(request(3L));

        assertThat(result.stockId()).isEqualTo(3L);
        InOrder order = inOrder(inventory, products);
        order.verify(inventory).getStock(3L);
        order.verify(products).create(any());
        order.verify(inventory).assignStock(8L, 3L);
    }

    @Test
    void unknownStockIsRejectedWithoutCreatingAProduct() {
        when(inventory.getStock(404L)).thenThrow(new StockNotFoundException(404L));

        assertThatThrownBy(() -> service.create(request(404L)))
                .isInstanceOf(StockNotFoundException.class)
                .hasMessage("Stock not found with id: 404");
        verifyNoInteractions(products);
        verify(inventory, never()).assignStock(any(), any());
    }

    @Test
    void unavailableInventoryDoesNotCreateAProduct() {
        when(inventory.getStock(3L))
                .thenThrow(new InventoryServiceUnavailableException("inventory-service is down"));

        assertThatThrownBy(() -> service.create(request(3L)))
                .isInstanceOf(InventoryServiceUnavailableException.class);
        verifyNoInteractions(products);
    }

    @Test
    void failedAssignmentRollsBackTheCatalogRow() {
        ProductResponse created = new ProductResponse(8L, "P-1", "Pen", 1.5f, null, null);
        when(inventory.getStock(3L)).thenReturn(new StockResponse(3L, "Main warehouse", 10, 2));
        when(products.create(any())).thenReturn(created);
        when(inventory.assignStock(8L, 3L))
                .thenThrow(new InventoryServiceUnavailableException("assignment failed"));

        assertThatThrownBy(() -> service.create(request(3L)))
                .isInstanceOf(InventoryServiceUnavailableException.class);
        verify(products).delete(8L);
    }
}
