package nmk_backend.service;

import nmk_backend.dto.PosCatalogResponse;
import nmk_backend.dto.PosLocationResponse;
import nmk_backend.entity.InventoryBalance;
import nmk_backend.entity.InventoryLocation;
import nmk_backend.entity.Product;
import nmk_backend.entity.ProductVariant;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.exception.InvalidInventoryOperationException;
import nmk_backend.repository.InventoryBalanceRepository;
import nmk_backend.repository.InventoryLocationRepository;
import nmk_backend.repository.ProductRepository;
import nmk_backend.repository.ProductVariantRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PosCatalogService {

    private final InventoryLocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final AdminLocationAccessService locationAccessService;

    public PosCatalogService(
            InventoryLocationRepository locationRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryBalanceRepository balanceRepository
    ) {
        this(locationRepository, productRepository, variantRepository, balanceRepository, null);
    }

    @Autowired
    public PosCatalogService(
            InventoryLocationRepository locationRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryBalanceRepository balanceRepository,
            AdminLocationAccessService locationAccessService
    ) {
        this.locationRepository = locationRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.locationAccessService = locationAccessService;
    }

    @Transactional(readOnly = true)
    public List<PosLocationResponse> getActiveLocations() {
        return locationRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparing(InventoryLocation::getCode))
                .map(PosLocationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PosLocationResponse> getActiveLocations(String username, String role) {
        if (locationAccessService == null) return getActiveLocations();
        List<Long> allowed = locationAccessService.getAccessibleLocationIds(username, role);
        return locationRepository.findAllById(allowed).stream()
                .filter(InventoryLocation::isActive)
                .sorted(Comparator.comparing(InventoryLocation::getCode))
                .map(PosLocationResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PosCatalogResponse getCatalog(Long locationId) {
        return getCatalog(locationId, null, null);
    }

    @Transactional(readOnly = true)
    public PosCatalogResponse getCatalog(Long locationId, String username, String role) {
        if (locationId == null || locationId <= 0) {
            throw new InvalidInventoryOperationException("A valid inventory location is required");
        }

        if (locationAccessService != null) locationAccessService.assertCanAccess(username, role, locationId);

        InventoryLocation location = locationRepository.findById(locationId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory location was not found"
                ));
        if (!location.isActive()) {
            throw new InvalidInventoryOperationException("Inventory location is inactive");
        }

        Map<Long, InventoryBalance> balances = balanceRepository.findByLocationId(locationId)
                .stream()
                .collect(Collectors.toMap(
                        balance -> balance.getVariant().getId(),
                        Function.identity()
                ));

        Map<Long, List<ProductVariant>> variantsByProduct = variantRepository.findAll().stream()
                .filter(ProductVariant::isActive)
                .collect(Collectors.groupingBy(variant -> variant.getProduct().getId()));

        List<Product> products = productRepository.findAll().stream()
                .filter(Product::isActive)
                .sorted(Comparator.comparing(Product::getName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        List<PosCatalogResponse.ProductItem> catalog = products.stream()
                .map(product -> {
                    List<PosCatalogResponse.VariantItem> variants =
                            variantsByProduct.getOrDefault(product.getId(), List.of())
                                    .stream()
                                    .sorted(Comparator.comparing(ProductVariant::getSku))
                                    .map(variant -> {
                                        InventoryBalance balance = balances.get(variant.getId());
                                        int onHand = balance == null ? 0 : balance.getOnHandQuantity();
                                        int reserved = balance == null ? 0 : balance.getReservedQuantity();
                                        BigDecimal price = variant.getSellingPriceOverride() != null
                                                ? variant.getSellingPriceOverride()
                                                : product.getPrice();

                                        return PosCatalogResponse.variant(
                                                variant, price, onHand, reserved,
                                                balance == null ? 0 : balance.getReorderLevel()
                                        );
                                    })
                                    .toList();

                    return PosCatalogResponse.product(product, variants);
                })
                .filter(product -> !product.variants().isEmpty())
                .toList();

        return new PosCatalogResponse(locationId, location.isStockCountLocked(), catalog);
    }
}
