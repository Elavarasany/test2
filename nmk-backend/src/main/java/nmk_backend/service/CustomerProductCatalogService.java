package nmk_backend.service;

import nmk_backend.dto.CustomerProductCatalogResponse;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.repository.InventoryBalanceRepository;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CustomerProductCatalogService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryLocationRepository locationRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final String checkoutLocationCode;

    public CustomerProductCatalogService(
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryLocationRepository locationRepository,
            InventoryBalanceRepository balanceRepository,
            @Value("${inventory.checkout.location-code:}") String checkoutLocationCode
    ) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.locationRepository = locationRepository;
        this.balanceRepository = balanceRepository;
        this.checkoutLocationCode = checkoutLocationCode;
    }

    @Transactional(readOnly = true)
    public CustomerProductCatalogResponse.ProductItem getActiveProductById(Long productId) {
        if (productId == null || productId <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }

        Product product = productRepository.findById(productId)
                .filter(Product::isActive)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Product not found"
                ));

        InventoryLocation location = getCheckoutLocation();
        List<ProductVariant> activeVariants =
                variantRepository.findByProductIdAndActiveTrue(productId).stream()
                        .filter(variant -> variant.getProduct().isActive())
                        .sorted(Comparator.comparing(
                                ProductVariant::getVariantName,
                                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                        ))
                        .toList();

        if (activeVariants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }

        Map<Long, InventoryBalance> balances = balanceRepository
                .findByLocationIdAndVariantIdIn(
                        location.getId(),
                        activeVariants.stream().map(ProductVariant::getId).toList()
                )
                .stream()
                .collect(Collectors.toMap(
                        balance -> balance.getVariant().getId(),
                        Function.identity()
                ));

        List<CustomerProductCatalogResponse.VariantItem> variants = activeVariants.stream()
                .map(variant -> {
                    InventoryBalance balance = balances.get(variant.getId());
                    int available = location.isStockCountLocked() || balance == null
                            ? 0 : balance.getAvailableQuantity();
                    BigDecimal price = variant.getSellingPriceOverride() != null
                            ? variant.getSellingPriceOverride() : product.getPrice();
                    return CustomerProductCatalogResponse.variant(variant, price, available);
                })
                .toList();

        BigDecimal displayPrice = variants.stream()
                .map(CustomerProductCatalogResponse.VariantItem::price)
                .min(BigDecimal::compareTo)
                .orElse(product.getPrice());
        int availableQuantity = variants.stream()
                .mapToInt(CustomerProductCatalogResponse.VariantItem::availableQuantity)
                .sum();

        return CustomerProductCatalogResponse.from(
                product, variants, displayPrice, availableQuantity
        );
    }

    private InventoryLocation getCheckoutLocation() {
        if (checkoutLocationCode == null || checkoutLocationCode.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Customer checkout inventory location is not configured"
            );
        }

        return locationRepository.findByCode(checkoutLocationCode.trim())
                .filter(InventoryLocation::isActive)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Customer checkout inventory location is missing or inactive"
                ));
    }

    @Transactional(readOnly = true)
    public Page<CustomerProductCatalogResponse.ProductItem> getActiveProducts(Pageable pageable) {
        InventoryLocation location = getCheckoutLocation();
        Page<Product> productPage = productRepository.findActiveProductsWithActiveVariants(pageable);
        List<Long> productIds = productPage.getContent().stream().map(Product::getId).toList();
        if (productIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, productPage.getTotalElements());
        }

        List<ProductVariant> activeVariants = variantRepository.findByProductIdInAndActiveTrue(productIds)
                .stream()
                .filter(variant -> variant.getProduct().isActive())
                .sorted(Comparator.comparing(
                        ProductVariant::getVariantName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                ))
                .toList();

        Map<Long, List<ProductVariant>> variantsByProduct = activeVariants.stream()
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        List<Long> variantIds = activeVariants.stream().map(ProductVariant::getId).toList();
        Map<Long, InventoryBalance> balances = variantIds.isEmpty()
                ? Map.of()
                : balanceRepository.findByLocationIdAndVariantIdIn(location.getId(), variantIds)
                        .stream()
                        .collect(Collectors.toMap(
                                balance -> balance.getVariant().getId(),
                                Function.identity()
                        ));

        List<CustomerProductCatalogResponse.ProductItem> content = productPage.getContent().stream()
                .map(product -> {
                    List<CustomerProductCatalogResponse.VariantItem> variants =
                            variantsByProduct.getOrDefault(product.getId(), List.of()).stream()
                                    .map(variant -> {
                                        InventoryBalance balance = balances.get(variant.getId());
                                        int available = location.isStockCountLocked() || balance == null
                                                ? 0 : balance.getAvailableQuantity();
                                        BigDecimal price = variant.getSellingPriceOverride() != null
                                                ? variant.getSellingPriceOverride() : product.getPrice();
                                        return CustomerProductCatalogResponse.variant(variant, price, available);
                                    })
                                    .toList();

                    BigDecimal displayPrice = variants.stream()
                            .map(CustomerProductCatalogResponse.VariantItem::price)
                            .min(BigDecimal::compareTo)
                            .orElse(product.getPrice());
                    int availableQuantity = variants.stream()
                            .mapToInt(CustomerProductCatalogResponse.VariantItem::availableQuantity)
                            .sum();

                    return CustomerProductCatalogResponse.from(
                            product, variants, displayPrice, availableQuantity
                    );
                })
                .toList();

        return new PageImpl<>(content, pageable, productPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<CustomerProductCatalogResponse.ProductItem> getActiveProducts() {
        if (checkoutLocationCode == null || checkoutLocationCode.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Customer checkout inventory location is not configured"
            );
        }

        InventoryLocation location = locationRepository.findByCode(checkoutLocationCode.trim())
                .filter(InventoryLocation::isActive)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Customer checkout inventory location is missing or inactive"
                ));

        Map<Long, InventoryBalance> balances = balanceRepository.findByLocationId(location.getId())
                .stream()
                .collect(Collectors.toMap(
                        balance -> balance.getVariant().getId(),
                        Function.identity()
                ));

        Map<Long, List<ProductVariant>> variantsByProduct = variantRepository.findAll().stream()
                .filter(ProductVariant::isActive)
                .filter(variant -> variant.getProduct().isActive())
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        return productRepository.findAll().stream()
                .filter(Product::isActive)
                .sorted(Comparator.comparing(
                        Product::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                ))
                .map(product -> {
                    List<CustomerProductCatalogResponse.VariantItem> variants =
                            variantsByProduct.getOrDefault(product.getId(), List.of())
                                    .stream()
                                    .sorted(Comparator.comparing(
                                            ProductVariant::getVariantName,
                                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                                    ))
                                    .map(variant -> {
                                        InventoryBalance balance = balances.get(variant.getId());
                                        // A stock count freezes every stock movement at this
                                        // location. Do not advertise purchasable quantity while
                                        // the physical count is in progress; reservation attempts
                                        // are intentionally rejected by InventoryService.
                                        int available = location.isStockCountLocked() || balance == null
                                                ? 0
                                                : balance.getAvailableQuantity();
                                        BigDecimal price = variant.getSellingPriceOverride() != null
                                                ? variant.getSellingPriceOverride()
                                                : product.getPrice();

                                        return CustomerProductCatalogResponse.variant(
                                                variant, price, available
                                        );
                                    })
                                    .toList();

                    if (variants.isEmpty()) {
                        return null;
                    }

                    BigDecimal displayPrice = variants.stream()
                            .map(CustomerProductCatalogResponse.VariantItem::price)
                            .min(BigDecimal::compareTo)
                            .orElse(product.getPrice());

                    int availableQuantity = variants.stream()
                            .mapToInt(CustomerProductCatalogResponse.VariantItem::availableQuantity)
                            .sum();

                    return CustomerProductCatalogResponse.from(
                            product, variants, displayPrice, availableQuantity
                    );
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
