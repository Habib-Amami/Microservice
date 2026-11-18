package org.example.achat.product.application;

import org.example.achat.product.api.dto.ProductRequest;
import org.example.achat.product.api.dto.ProductResponse;
import org.example.achat.product.domain.Product;
import org.example.achat.product.domain.ProductCategory;
import org.example.achat.product.infrastructure.persistence.ProductCategoryRepository;
import org.example.achat.product.infrastructure.persistence.ProductRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductApplicationServiceTest {

    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductCategoryRepository categoryRepository = mock(ProductCategoryRepository.class);
    private final ProductApplicationService service =
            new ProductApplicationService(repository, categoryRepository);

    @Test
    void updatePreservesCreationDateAndCategoryWhileUpdatingLegacyFields() {
        LocalDate created = LocalDate.of(2024, 3, 10);
        LocalDate modified = LocalDate.of(2025, 6, 12);
        ProductCategory category = new ProductCategory();
        category.setIdCategorieProduit(11L);
        Product existing = new Product();
        existing.setIdProduit(7L);
        existing.setDateCreation(created);
        existing.setCategorieProduit(category);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        ProductResponse response = service.update(
                7L, new ProductRequest("P-7", "Updated", 8.5f, LocalDate.of(2026, 1, 1), modified, null));

        assertThat(response.idProduit()).isEqualTo(7L);
        assertThat(response.prix()).isEqualTo(8.5f);
        assertThat(response.categorieProduitId()).isEqualTo(11L);
        assertThat(existing.getDateCreation()).isEqualTo(created);
        assertThat(existing.getDateDerniereModification()).isEqualTo(modified);
        assertThat(existing.getCategorieProduit()).isSameAs(category);
        verify(repository).save(existing);
    }

    @Test
    void returnsNotFoundForUnknownProduct() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(404L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("Produit not found with id: 404");
    }

    @Test
    void createAssignsCategoryWhenProvided() {
        ProductCategory category = new ProductCategory();
        category.setIdCategorieProduit(3L);
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(category));
        when(repository.save(org.mockito.ArgumentMatchers.any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service.create(
                new ProductRequest("P-3", "Categorised", 4.0f, LocalDate.of(2026, 1, 1), null, 3L));

        assertThat(response.categorieProduitId()).isEqualTo(3L);
    }

    @Test
    void createRejectsUnknownCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(
                new ProductRequest("P-99", "No category", 1.0f, LocalDate.of(2026, 1, 1), null, 99L)))
                .isInstanceOf(ProductCategoryNotFoundException.class);
    }

    @Test
    void storesStockProjectionPushedByInventory() {
        Product product = new Product();
        product.setIdProduit(5L);
        when(repository.findById(5L)).thenReturn(Optional.of(product));
        when(repository.save(org.mockito.ArgumentMatchers.any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service.updateStockAssignment(5L, 9L);

        assertThat(response.stockId()).isEqualTo(9L);
        assertThat(product.getStockId()).isEqualTo(9L);
    }

    @Test
    void clearsStockProjectionWhenInventoryReportsNoStock() {
        Product product = new Product();
        product.setIdProduit(5L);
        product.setStockId(9L);
        when(repository.findById(5L)).thenReturn(Optional.of(product));
        when(repository.save(org.mockito.ArgumentMatchers.any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = service.updateStockAssignment(5L, null);

        assertThat(response.stockId()).isNull();
    }

    @Test
    void rejectsStockProjectionForUnknownProduct() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStockAssignment(404L, 1L))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
