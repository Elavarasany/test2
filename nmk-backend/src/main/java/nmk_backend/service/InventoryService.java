
package nmk_backend.service;

import nmk_backend.entity.*;
import nmk_backend.exception.*;
import nmk_backend.repository.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class InventoryService {

    private final InventoryLocationRepository locationRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final InventoryBalanceRepository balanceRepository;
    private final StockMovementRepository movementRepository;

    public InventoryService(
            InventoryLocationRepository locationRepository,
            ProductRepository productRepository,
            ProductVariantRepository variantRepository,
            InventoryBalanceRepository balanceRepository,
            StockMovementRepository movementRepository
    ) {
        this.locationRepository = locationRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.balanceRepository = balanceRepository;
        this.movementRepository = movementRepository;
    }

    @Transactional
    public InventoryBalance receiveStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason
    ) {
        return receiveStock(locationId, variantId, quantity,
                idempotencyKey, actorStaffId, reason, null, null);
    }

    @Transactional
    public InventoryBalance receiveStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            String referenceType, Long referenceId
    ) {
        validateQuantity(quantity);
        return execute(locationId, variantId, quantity, idempotencyKey,
                actorStaffId, reason, "RECEIPT", quantity, 0, true,
                referenceType, referenceId);
    }

    @Transactional
    public InventoryBalance receiveStockByAdmin(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorAdminId, String reason
    ) {
        validateQuantity(quantity);
        if (actorAdminId == null || actorAdminId <= 0) {
            throw new InvalidInventoryOperationException("A valid admin identity is required");
        }
        return execute(
                locationId, variantId, quantity, idempotencyKey,
                null, actorAdminId, reason, "RECEIPT", quantity, 0, true,
                null, null
        );
    }

    @Transactional
    public InventoryBalance reserveStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason
    ) {
        return reserveStock(locationId, variantId, quantity,
                idempotencyKey, actorStaffId, reason, null, null);
    }

    @Transactional
    public InventoryBalance reserveStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            String referenceType, Long referenceId
    ) {
        validateQuantity(quantity);
        return execute(locationId, variantId, quantity, idempotencyKey,
                actorStaffId, reason, "RESERVATION", 0, quantity, false,
                referenceType, referenceId);
    }

    @Transactional
    public InventoryBalance releaseReservation(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason
    ) {
        return releaseReservation(locationId, variantId, quantity,
                idempotencyKey, actorStaffId, reason, null, null);
    }

    @Transactional
    public InventoryBalance releaseReservation(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            String referenceType, Long referenceId
    ) {
        validateQuantity(quantity);
        return execute(locationId, variantId, quantity, idempotencyKey,
                actorStaffId, reason, "RELEASE", 0, -quantity, false,
                referenceType, referenceId);
    }

    @Transactional
    public InventoryBalance consumeReservation(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason
    ) {
        return consumeReservation(locationId, variantId, quantity,
                idempotencyKey, actorStaffId, reason, null, null);
    }

    @Transactional
    public InventoryBalance consumeReservation(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            String referenceType, Long referenceId
    ) {
        validateQuantity(quantity);
        return execute(locationId, variantId, quantity, idempotencyKey,
                actorStaffId, reason, "CONSUMPTION", -quantity, -quantity,
                false, referenceType, referenceId);
    }

    @Transactional
    public InventoryBalance sellStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            String referenceType, Long referenceId
    ) {
        validateQuantity(quantity);

        return execute(
                locationId,
                variantId,
                quantity,
                idempotencyKey,
                actorStaffId,
                reason,
                "SALE",
                -quantity,
                0,
                false,
                referenceType,
                referenceId
        );
    }

    @Transactional
    public InventoryBalance updateReorderLevel(
            Long locationId, Long variantId, int reorderLevel,
            String actorUsername
    ) {
        if (locationId == null || locationId <= 0 || variantId == null || variantId <= 0) {
            throw new InvalidInventoryOperationException("A valid location and product variant are required");
        }
        if (reorderLevel < 0 || reorderLevel > 1_000_000) {
            throw new InvalidInventoryOperationException("Reorder level must be between 0 and 1,000,000");
        }
        if (actorUsername == null || actorUsername.isBlank()) {
            throw new InvalidInventoryOperationException("An authenticated staff username is required");
        }

        InventoryLocation location = locationRepository.findByIdForUpdate(locationId)
                .orElseThrow(() -> new InventoryNotFoundException("Inventory location was not found"));
        if (!location.isActive()) {
            throw new InvalidInventoryOperationException("Inventory location is inactive");
        }
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new InventoryNotFoundException("Product variant was not found"));

        InventoryBalance balance = balanceRepository
                .findByLocationAndVariantForUpdate(locationId, variantId)
                .orElseGet(() -> {
                    InventoryBalance created = new InventoryBalance();
                    created.setLocation(location);
                    created.setVariant(variant);
                    created.setOnHandQuantity(0);
                    created.setReservedQuantity(0);
                    return created;
                });

        balance.setReorderLevel(reorderLevel);
        balance.setReorderLevelUpdatedBy(actorUsername.trim());
        balance.setReorderLevelUpdatedAt(java.time.LocalDateTime.now());
        return balanceRepository.saveAndFlush(balance);
    }

    @Transactional(readOnly = true)
    public java.util.List<nmk_backend.dto.LowStockAlertResponse> getLowStockBalances(Long locationId) {
        if (locationId == null || locationId <= 0) {
            throw new InvalidInventoryOperationException("A valid inventory location is required");
        }
        return balanceRepository.findByLocationId(locationId).stream()
                .filter(balance -> balance.getReorderLevel() != null && balance.getReorderLevel() > 0)
                .filter(balance -> balance.getAvailableQuantity() <= balance.getReorderLevel())
                .sorted(java.util.Comparator.comparing(
                        balance -> balance.getVariant().getSku(),
                        java.util.Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)
                ))
                .map(nmk_backend.dto.LowStockAlertResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<nmk_backend.dto.InventoryMovementResponse> getMovementHistory(
            Long locationId,
            Long variantId,
            int page,
            int size
    ) {
        if (locationId == null || locationId <= 0) {
            throw new InvalidInventoryOperationException("A valid inventory location is required");
        }
        if (variantId != null && variantId <= 0) {
            throw new InvalidInventoryOperationException("A valid product variant is required");
        }
        if (page < 0) {
            throw new InvalidInventoryOperationException("Page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw new InvalidInventoryOperationException("Page size must be between 1 and 100");
        }
        if (locationRepository.findById(locationId).isEmpty()) {
            throw new InventoryNotFoundException("Inventory location was not found");
        }
        if (variantId != null && variantRepository.findById(variantId).isEmpty()) {
            throw new InventoryNotFoundException("Product variant was not found");
        }

        return movementRepository.findHistory(
                locationId,
                variantId,
                org.springframework.data.domain.PageRequest.of(page, size)
        ).map(nmk_backend.dto.InventoryMovementResponse::from);
    }

    @Transactional
    public InventoryBalance receivePurchaseOrderStock(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            Long goodsReceiptId
    ) {
        validateQuantity(quantity);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException("A purchase receipt reason is required");
        }
        if (goodsReceiptId == null || goodsReceiptId <= 0) {
            throw new InvalidInventoryOperationException("A valid goods receipt reference is required");
        }
        return execute(
                locationId, variantId, quantity, idempotencyKey, actorStaffId,
                reason.trim(), "RECEIPT", quantity, 0, true,
                "GOODS_RECEIPT", goodsReceiptId
        );
    }

    @Transactional
    public InventoryBalance returnStockToSupplier(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            Long supplierReturnId
    ) {
        validateQuantity(quantity);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException("A supplier return reason is required");
        }
        if (supplierReturnId == null || supplierReturnId <= 0) {
            throw new InvalidInventoryOperationException("A valid supplier return reference is required");
        }
        return execute(
                locationId, variantId, quantity, idempotencyKey, actorStaffId,
                reason.trim(), "SUPPLIER_RETURN", -quantity, 0, false,
                "SUPPLIER_RETURN", supplierReturnId
        );
    }

    @Transactional
    public InventoryBalance applyStockCountVariance(
            Long locationId, Long variantId, int quantityDelta,
            String idempotencyKey, Long actorStaffId, String reason,
            Long stockCountSessionId
    ) {
        if (quantityDelta == 0) {
            throw new InvalidInventoryOperationException("A zero stock count variance does not create a movement");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException("A stock count variance reason is required");
        }
        if (stockCountSessionId == null || stockCountSessionId <= 0) {
            throw new InvalidInventoryOperationException("A valid stock count session reference is required");
        }
        return execute(
                locationId, variantId, Math.abs(quantityDelta), idempotencyKey,
                actorStaffId, reason.trim(), "COUNT_VARIANCE", quantityDelta, 0,
                quantityDelta > 0, "STOCK_COUNT", stockCountSessionId
        );
    }

    @Transactional
    public InventoryBalance restockCustomerReturn(
            Long locationId, Long variantId, int quantity,
            String idempotencyKey, Long actorStaffId, String reason,
            Long returnId
    ) {
        validateQuantity(quantity);
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException(
                    "A customer return restock reason of 1 to 500 characters is required"
            );
        }
        if (returnId == null || returnId <= 0) {
            throw new InvalidInventoryOperationException("A valid return reference is required");
        }
        return execute(
                locationId, variantId, quantity, idempotencyKey, actorStaffId,
                reason.trim(), "CUSTOMER_RETURN_RESTOCK", quantity, 0, false,
                "POS_RETURN", returnId
        );
    }

    @Transactional
    public InventoryBalance adjustStock(
            Long locationId, Long variantId, int quantityDelta,
            String idempotencyKey, Long actorStaffId, String reason
    ) {
        if (quantityDelta == 0) {
            throw new InvalidInventoryOperationException(
                    "Stock adjustment must change the quantity"
            );
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException(
                    "A stock adjustment reason of 1 to 500 characters is required"
            );
        }
        return execute(
                locationId, variantId, 1, idempotencyKey, actorStaffId,
                reason.trim(), "ADJUSTMENT", quantityDelta, 0, false,
                null, null
        );
    }

    @Transactional
    public InventoryBalance adjustStockByAdmin(
            Long locationId, Long variantId, int quantityDelta,
            String idempotencyKey, Long actorAdminId, String reason
    ) {
        if (actorAdminId == null || actorAdminId <= 0) {
            throw new InvalidInventoryOperationException("A valid admin identity is required");
        }
        if (quantityDelta == 0) {
            throw new InvalidInventoryOperationException(
                    "Stock adjustment must change the quantity"
            );
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new InvalidInventoryOperationException(
                    "A stock adjustment reason of 1 to 500 characters is required"
            );
        }
        return execute(
                locationId, variantId, 1, idempotencyKey,
                null, actorAdminId, reason.trim(), "ADJUSTMENT",
                quantityDelta, 0, false, null, null
        );
    }

    private InventoryBalance execute(
            Long locationId,
            Long variantId,
            int quantity,
            String idempotencyKey,
            Long actorStaffId,
            String reason,
            String movementType,
            int quantityDelta,
            int reservedDelta,
            boolean allowBalanceCreation,
            String referenceType,
            Long referenceId
    ) {
        return execute(
                locationId, variantId, quantity, idempotencyKey,
                actorStaffId, null, reason, movementType,
                quantityDelta, reservedDelta, allowBalanceCreation,
                referenceType, referenceId
        );
    }

    private InventoryBalance execute(
            Long locationId,
            Long variantId,
            int quantity,
            String idempotencyKey,
            Long actorStaffId,
            Long actorAdminId,
            String reason,
            String movementType,
            int quantityDelta,
            int reservedDelta,
            boolean allowBalanceCreation,
            String referenceType,
            Long referenceId
    ) {
        if (actorStaffId != null && actorAdminId != null) {
            throw new InvalidInventoryOperationException(
                    "A stock movement cannot have both staff and admin actors"
            );
        }
        validateIds(locationId, variantId);

        String normalizedKey =
                validateAndNormalizeIdempotencyKey(idempotencyKey);

        validateReference(referenceType, referenceId);

        /*
         * Lock order must remain consistent:
         * location -> product -> variant -> balance.
         */
        InventoryLocation location = locationRepository
                .findByIdForUpdate(locationId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory location not found"
                ));

        Long productId = variantRepository
                .findProductIdByVariantId(variantId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Product variant not found"
                ));

        Product product = productRepository
                .findByIdForUpdate(productId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Parent product not found"
                ));

        ProductVariant variant = variantRepository
                .findByIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Product variant not found"
                ));

        if (variant.getProduct() == null
                || !Objects.equals(
                productId,
                variant.getProduct().getId()
        )) {
            throw new InventoryConflictException(
                    "Product variant parent changed during inventory operation"
            );
        }

        /*
         * Idempotency is checked before active-status validation.
         *
         * A completed operation must remain safely retryable even if
         * its location, product, or variant was deactivated afterward.
         *
         * Do not weaken these comparisons. Reusing a key for a different
         * operation or reference must always be rejected.
         */
        var existingMovement =
                movementRepository.findByIdempotencyKey(normalizedKey);

        if (existingMovement.isPresent()) {
            StockMovement existing = existingMovement.get();

            boolean sameOperation =
                    existing.getLocation() != null
                            && Objects.equals(
                            existing.getLocation().getId(),
                            locationId
                    )
                            && existing.getVariant() != null
                            && Objects.equals(
                            existing.getVariant().getId(),
                            variantId
                    )
                            && Objects.equals(
                            existing.getMovementType(),
                            movementType
                    )
                            && Objects.equals(
                            existing.getQuantityDelta(),
                            quantityDelta
                    )
                            && Objects.equals(
                            existing.getReservedDelta(),
                            reservedDelta
                    )
                            && Objects.equals(
                            existing.getReferenceType(),
                            referenceType
                    )
                            && Objects.equals(
                            existing.getReferenceId(),
                            referenceId
                    )
                            && Objects.equals(
                            existing.getActorStaffId(),
                            actorStaffId
                    )
                            && Objects.equals(
                            existing.getActorAdminId(),
                            actorAdminId
                    )
                            && Objects.equals(
                            existing.getReason(),
                            reason
                    );

            if (!sameOperation) {
                throw new InventoryConflictException(
                        "Idempotency key was already used for another operation or reference"
                );
            }

            return balanceRepository
                    .findByLocationIdAndVariantId(locationId, variantId)
                    .orElseThrow(() -> new InventoryConflictException(
                            "Previous movement exists but its balance is missing"
                    ));
        }

        /*
         * New receipts and reservations must use active records.
         *
         * Releases and consumptions are allowed for inactive records:
         * deactivation must not strand stock already reserved by an order.
         */
        if (location.isStockCountLocked() && !"COUNT_VARIANCE".equals(movementType)) {
            throw new InventoryConflictException(
                    "Inventory is frozen while a physical stock count is in progress"
            );
        }

        boolean requiresActiveRecords =
                "RECEIPT".equals(movementType)
                        || "RESERVATION".equals(movementType)
                        || "SALE".equals(movementType)
                        || "ADJUSTMENT".equals(movementType)
                        || "CUSTOMER_RETURN_RESTOCK".equals(movementType);

        if (requiresActiveRecords) {
            if (!location.isActive()) {
                throw new InvalidInventoryOperationException(
                        "Inventory location is inactive"
                );
            }

            if (!product.isActive()) {
                throw new InvalidInventoryOperationException(
                        "Parent product is inactive"
                );
            }

            if (!variant.isActive()) {
                throw new InvalidInventoryOperationException(
                        "Product variant is inactive"
                );
            }
        }

        InventoryBalance balance = balanceRepository
                .findByLocationAndVariantForUpdate(locationId, variantId)
                .orElse(null);

        if (balance == null) {
            if (!allowBalanceCreation) {
                throw new InventoryNotFoundException(
                        "Inventory balance not found. Receive stock first."
                );
            }

            balance = new InventoryBalance();
            balance.setLocation(location);
            balance.setVariant(variant);
            balance.setOnHandQuantity(0);
            balance.setReservedQuantity(0);
            balance.setReorderLevel(0);
        }

        int currentOnHand = balance.getOnHandQuantity();
        int currentReserved = balance.getReservedQuantity();

        if ((quantityDelta == 0 && reservedDelta > 0)
                || "SALE".equals(movementType)) {

            int available = currentOnHand - currentReserved;

            if (available < quantity) {
                throw new InsufficientStockException(
                        "Insufficient available stock. Available: " + available
                );
            }
        }

        if (reservedDelta < 0 && currentReserved < quantity) {
            throw new InsufficientStockException(
                    "Reserved quantity is insufficient. Reserved: "
                            + currentReserved
            );
        }

        int newOnHand;
        int newReserved;

        try {
            newOnHand = Math.addExact(currentOnHand, quantityDelta);
            newReserved = Math.addExact(currentReserved, reservedDelta);
        } catch (ArithmeticException exception) {
            throw new InvalidInventoryOperationException(
                    "Inventory quantity exceeds the supported limit"
            );
        }

        if (newOnHand < 0
                || newReserved < 0
                || newReserved > newOnHand) {
            throw new InvalidInventoryOperationException(
                    "Operation would create an invalid inventory balance"
            );
        }

        balance.setOnHandQuantity(newOnHand);
        balance.setReservedQuantity(newReserved);

        balanceRepository.saveAndFlush(balance);

        StockMovement movement = new StockMovement();
        movement.setLocation(location);
        movement.setVariant(variant);
        movement.setMovementType(movementType);
        movement.setQuantityDelta(quantityDelta);
        movement.setReservedDelta(reservedDelta);
        movement.setOnHandAfter(newOnHand);
        movement.setReservedAfter(newReserved);
        movement.setReferenceType(referenceType);
        movement.setReferenceId(referenceId);
        movement.setIdempotencyKey(normalizedKey);
        movement.setActorStaffId(actorStaffId);
        movement.setActorAdminId(actorAdminId);
        movement.setReason(reason);

        movementRepository.saveAndFlush(movement);

        return balance;
    }

    private void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new InvalidInventoryOperationException(
                    "Quantity must be greater than zero"
            );
        }
    }

    private void validateIds(Long locationId, Long variantId) {
        if (locationId == null || variantId == null
                || locationId <= 0 || variantId <= 0) {
            throw new InvalidInventoryOperationException(
                    "Valid location and variant IDs are required"
            );
        }
    }

    private String validateAndNormalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 150) {
            throw new InvalidInventoryOperationException(
                    "A valid idempotency key is required (maximum 150 characters)"
            );
        }

        return key.trim();
    }

    private void validateReference(String referenceType, Long referenceId) {
        if (referenceType != null
                && (referenceType.isBlank()
                || referenceType.trim().length() > 40)) {
            throw new InvalidInventoryOperationException(
                    "Reference type must contain 1 to 40 characters"
            );
        }

        if (referenceId != null && referenceId <= 0) {
            throw new InvalidInventoryOperationException(
                    "Reference ID must be greater than zero"
            );
        }

        if ((referenceType == null) != (referenceId == null)) {
            throw new InvalidInventoryOperationException(
                    "Reference type and reference ID must be supplied together"
            );
        }
    }
}
