package org.example.achat.inventory.application;

import feign.FeignException;
import org.example.achat.inventory.api.dto.StockRequest;
import org.example.achat.inventory.domain.Stock;
import org.example.achat.inventory.domain.StockProductAssignment;
import org.example.achat.inventory.infrastructure.persistence.StockProductAssignmentRepository;
import org.example.achat.inventory.infrastructure.persistence.StockRepository;
import org.example.achat.inventory.infrastructure.product.ProductClient;
import org.example.achat.inventory.infrastructure.product.ProductStockProjectionClient;
import org.example.achat.inventory.infrastructure.product.dto.ProductResponse;
import org.example.achat.inventory.infrastructure.product.dto.StockAssignmentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockRepository stockRepository;
    private final StockProductAssignmentRepository assignmentRepository;
    private final ProductClient productClient;
    private final ProductStockProjectionClient projectionClient;

    public StockService(
            StockRepository stockRepository,
            StockProductAssignmentRepository assignmentRepository,
            ProductClient productClient,
            ProductStockProjectionClient projectionClient) {
        this.stockRepository = stockRepository;
        this.assignmentRepository = assignmentRepository;
        this.productClient = productClient;
        this.projectionClient = projectionClient;
    }

    @Transactional(readOnly = true)
    public List<Stock> findAll() {
        return stockRepository.findAll();
    }

    @Transactional(readOnly = true)
    public String generateLowStockReport() {
        StringBuilder report = new StringBuilder();
        for (Stock stock : stockRepository.findStocksBelowMinimumQuantity()) {
            report.append(": le stock ")
                    .append(stock.getLibelleStock())
                    .append(" a une quantité de ")
                    .append(stock.getQte())
                    .append(" inférieur à la quantité minimale a ne pas dépasser de ")
                    .append(stock.getQteMin())
                    .append(System.lineSeparator());
        }
        return report.toString();
    }

    @Transactional(readOnly = true)
    public Stock findById(Long stockId) {
        return stockRepository.findById(stockId)
                .orElseThrow(() -> new StockNotFoundException(stockId));
    }

    @Transactional
    public Stock create(StockRequest request) {
        Stock stock = new Stock();
        apply(stock, request);
        return stockRepository.save(stock);
    }

    @Transactional
    public Stock update(Long stockId, StockRequest request) {
        Stock stock = findById(stockId);
        apply(stock, request);
        return stockRepository.save(stock);
    }

    @Transactional
    public void delete(Long stockId) {
        if (!stockRepository.existsById(stockId)) {
            throw new StockNotFoundException(stockId);
        }
        if (assignmentRepository.existsByStock_IdStock(stockId)) {
            throw new StockInUseException(stockId);
        }
        stockRepository.deleteById(stockId);
    }

    @Transactional
    public ProductResponse assignProduct(Long productId, Long stockId) {
        ProductResponse product = findProduct(productId);
        Stock stock = findById(stockId);

        StockProductAssignment assignment = assignmentRepository.findFirstByProductId(productId);
        if (assignment == null) {
            assignment = new StockProductAssignment();
            assignment.setProductId(productId);
        }
        assignment.setStock(stock);
        assignmentRepository.save(assignment);
        projectStockAssignment(productId, stockId);

        return product.withStockId(stockId);
    }

    /**
     * Mirrors the assignment into product-service's read model so product
     * responses can expose stockId. The assignment is already committed to
     * this service's database, so a projection failure is logged rather than
     * failing the caller; re-assigning or reseeding repairs it.
     */
    private void projectStockAssignment(Long productId, Long stockId) {
        try {
            projectionClient.assignStock(productId, new StockAssignmentRequest(stockId));
        } catch (FeignException exception) {
            log.warn("Product {} was assigned to stock {}, but the product-service "
                     + "stock projection was not updated: {}",
                    productId, stockId, exception.getMessage());
        }
    }

    private ProductResponse findProduct(Long productId) {
        try {
            ProductResponse product = productClient.getProduct(productId);
            if (product == null) {
                throw new ProductServiceUnavailableException(
                        new IllegalStateException("Product Service returned an empty response"));
            }
            return product;
        } catch (FeignException.NotFound exception) {
            throw new ProductNotFoundException(productId);
        } catch (FeignException exception) {
            throw new ProductServiceUnavailableException(exception);
        }
    }

    private void apply(Stock stock, StockRequest request) {
        stock.setLibelleStock(request.libelleStock());
        stock.setQte(request.qte());
        stock.setQteMin(request.qteMin());
    }
}
