package nmk_backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import nmk_backend.dto.*;
import nmk_backend.entity.*;
import nmk_backend.exception.InventoryNotFoundException;
import nmk_backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class PosExchangeService {
 private static final BigDecimal ZERO=new BigDecimal("0.00");
 private static final Set<String> METHODS=Set.of("CASH","UPI","CARD");
 private final InventoryLocationRepository locations;
 private final PosSaleRepository sales;
 private final PosReturnRepository returns;
 private final PosReturnItemRepository returnItems;
 private final PosExchangeRepository exchanges;
 private final PosExchangeCreditSettlementRepository credits;
 private final ProductRepository products;
 private final ProductVariantRepository variants;
 private final AdminRepository admins;
 private final InventoryService inventory;
 private final PosShiftService shifts;
 private final ObjectMapper mapper;

 public PosExchangeService(InventoryLocationRepository locations,PosSaleRepository sales,PosReturnRepository returns,
  PosReturnItemRepository returnItems,PosExchangeRepository exchanges,PosExchangeCreditSettlementRepository credits,
  ProductRepository products,ProductVariantRepository variants,AdminRepository admins,InventoryService inventory,
  PosShiftService shifts,ObjectMapper mapper) {
  this.locations=locations; this.sales=sales; this.returns=returns; this.returnItems=returnItems;
  this.exchanges=exchanges; this.credits=credits; this.products=products; this.variants=variants;
  this.admins=admins; this.inventory=inventory; this.shifts=shifts; this.mapper=mapper;
 }

 @Transactional(readOnly=true)
 public List<PosExchangeResponse> getExchanges(Long originalSaleId) { return getExchanges(originalSaleId,null,null); }

 @Transactional(readOnly=true)
 public List<PosExchangeResponse> getExchanges(Long originalSaleId,String username,String role) {
  if(originalSaleId==null||originalSaleId<=0) throw new IllegalArgumentException("A valid original sale ID is required");
  PosSale original=sales.findById(originalSaleId).orElseThrow(()->new InventoryNotFoundException("Original POS sale was not found"));
  shifts.assertLocationAccessForStaff(username,role,original.getLocationId());
  return exchanges.findByOriginalSaleIdOrderByCreatedAtDesc(originalSaleId).stream()
   .map(PosExchangeResponse::from).toList();
 }

 @Transactional(isolation=Isolation.READ_COMMITTED)
 public PosExchangeResponse exchange(Long saleId,PosExchangeRequest req,String username,String role) {
  validate(saleId,req,username);
  String key=req.idempotencyKey().trim(), fp=fingerprint(saleId,req,username);
  PosSale snapshot=sales.findById(saleId).orElseThrow(()->new InventoryNotFoundException("Original POS sale was not found"));
  shifts.assertLocationAccessForStaff(username,role,snapshot.getLocationId());
  var prior=exchanges.findByIdempotencyKey(key);
  if(prior.isPresent()) return replay(prior.get(),fp);

  
  InventoryLocation location=locations.findByIdForUpdate(snapshot.getLocationId())
   .orElseThrow(()->new InventoryNotFoundException("Original sale location was not found"));
  if(!location.isActive()) throw new IllegalArgumentException("Original sale location is inactive");
  PosShift shift=shifts.requireOpenShiftForManagerAction(req.shiftId(),location.getId(),username,role);
  PosSale original=sales.findByIdForUpdate(saleId).orElseThrow(()->new InventoryNotFoundException("Original POS sale was not found"));
  var raced=exchanges.findByIdempotencyKeyForUpdate(key);
  if(raced.isPresent()) return replay(raced.get(),fp);
  if(!"COMPLETED".equals(original.getStatus())) throw new IllegalArgumentException("Only completed sales can be exchanged");
  Admin actor=admins.findByUsernameIgnoreCase(username).orElseThrow(()->new IllegalArgumentException("Authenticated staff account was not found"));

  String staffRole=role==null?"":role.trim().toUpperCase(Locale.ROOT);
  if(!Set.of("ADMIN","MANAGER","CASHIER").contains(staffRole)) throw new IllegalArgumentException("A valid POS staff role is required");
  BigDecimal discount=money(req.replacementDiscountAmount(),"Replacement discount");
  if(discount.signum()<0) throw new IllegalArgumentException("Replacement discount cannot be negative");
  if(discount.signum()>0&&"CASHIER".equals(staffRole)) throw new IllegalArgumentException("Only ADMIN or MANAGER staff may apply a replacement discount");

  Map<Long,PosSaleItem> originalLines=new HashMap<>();
  original.getItems().forEach(i->originalLines.put(i.getId(),i));
  Set<Long> seen=new HashSet<>();
  PosReturn ret=new PosReturn();
  ret.setSale(original); ret.setShift(shift); ret.setProcessedByUsername(username);
  ret.setReason(req.reason().trim()); ret.setReturnType("EXCHANGE"); ret.setStatus("COMPLETED");
  ret.setIdempotencyKey("POS-EXCHANGE-RETURN:"+key); ret.setRequestFingerprint(fp);
  BigDecimal returned=ZERO;
  for(PosReturnItemRequest line:req.returnItems()) {
   if(!seen.add(line.saleItemId())) throw new IllegalArgumentException("An original sale line can appear only once");
   PosSaleItem sold=originalLines.get(line.saleItemId());
   if(sold==null) throw new IllegalArgumentException("A returned line does not belong to the original sale");
   long oldQty=Optional.ofNullable(returnItems.sumReturnedQuantityBySaleItemId(sold.getId())).orElse(0L);
   int remaining=sold.getQuantity()-Math.toIntExact(oldQty);
   if(line.quantity()>remaining) throw new IllegalArgumentException("Exchange quantity exceeds remaining quantity for SKU "+sold.getSku());
   String disposition=normalizeDisposition(line.restockDisposition());
   String why=line.dispositionReason()==null?null:line.dispositionReason().trim();
   if(!"RESTOCK".equals(disposition)&&(why==null||why.isBlank())) throw new IllegalArgumentException("A reason is required for damaged or discarded items");
   BigDecimal net=PosReturnPricing.netLineValue(original,sold);
   BigDecimal already=Optional.ofNullable(returnItems.sumRefundedAmountBySaleItemId(sold.getId())).orElse(ZERO);
   BigDecimal value=PosReturnPricing.refundForQuantity(net,sold.getQuantity(),remaining,line.quantity(),already);
   PosReturnItem ri=new PosReturnItem(); ri.setReturnRecord(ret); ri.setSaleItem(sold); ri.setQuantity(line.quantity());
   ri.setRefundAmount(value); ri.setUnitRefundAmount(value.divide(BigDecimal.valueOf(line.quantity()),2,RoundingMode.HALF_UP));
   ri.setRestockDisposition(disposition); ri.setDispositionReason(why); ret.getItems().add(ri); returned=returned.add(value);
  }
  returned=returned.setScale(2,RoundingMode.HALF_UP);
  BigDecimal priorReturns=Optional.ofNullable(returns.sumRefundTotalBySaleId(saleId)).orElse(ZERO);
  if(priorReturns.add(returned).compareTo(original.getGrandTotal())>0) throw new IllegalArgumentException("Cumulative returns cannot exceed original sale total");

  PosSale replacement=new PosSale();
  replacement.setLocationId(location.getId()); replacement.setShiftId(shift.getId()); replacement.setCashierUsername(username);
  replacement.setCustomerName(original.getCustomerName()); replacement.setCustomerPhone(original.getCustomerPhone());
  replacement.setDiscountAmount(discount); replacement.setIdempotencyKey("POS-EXCHANGE-SALE:"+key); replacement.setRequestFingerprint(fp);
  Set<Long> seenVariants=new HashSet<>(); BigDecimal subtotal=ZERO;
  var lines=req.replacementItems().stream().sorted(Comparator.comparing(PosSaleItemRequest::getProductId).thenComparing(PosSaleItemRequest::getVariantId)).toList();
  for(PosSaleItemRequest line:lines) {
   if(!seenVariants.add(line.getVariantId())) throw new IllegalArgumentException("A replacement variant can appear only once");
   Product product=products.findByIdForUpdate(line.getProductId()).orElseThrow(()->new InventoryNotFoundException("Replacement product was not found"));
   ProductVariant variant=variants.findByIdForUpdate(line.getVariantId()).orElseThrow(()->new InventoryNotFoundException("Replacement variant was not found"));
   if(!Objects.equals(variant.getProduct().getId(),product.getId())) throw new IllegalArgumentException("Replacement variant does not belong to product");
   if(!product.isActive()||!variant.isActive()) throw new IllegalArgumentException("Inactive products or variants cannot be sold");
   BigDecimal price=money(variant.getSellingPriceOverride()!=null?variant.getSellingPriceOverride():product.getPrice(),"Replacement unit price");
   if(price.signum()<=0) throw new IllegalArgumentException("Replacement product has no valid selling price");
   BigDecimal total=price.multiply(BigDecimal.valueOf(line.getQuantity())).setScale(2,RoundingMode.UNNECESSARY); subtotal=subtotal.add(total);
   PosSaleItem item=new PosSaleItem(); item.setSale(replacement); item.setProductId(product.getId()); item.setVariantId(variant.getId());
   item.setSku(variant.getSku()); item.setProductName(product.getName()); item.setVariantName(variant.getVariantName());
   try { item.setVariantAttributesJson(mapper.writeValueAsString(variant.getAttributes())); }
   catch(JsonProcessingException ex) { throw new IllegalStateException("Unable to snapshot replacement variant",ex); }
   item.setQuantity(line.getQuantity()); item.setUnitPrice(price); item.setLineTotal(total); replacement.getItems().add(item);
  }
  if(discount.compareTo(subtotal)>0) throw new IllegalArgumentException("Discount cannot exceed replacement subtotal");
  BigDecimal replacementValue=subtotal.subtract(discount).setScale(2,RoundingMode.HALF_UP);
  PosExchangeSettlement.Result settlement=PosExchangeSettlement.calculate(returned,replacementValue);
  attachCollection(req.collectionPayments(),replacement,settlement.amountCollected());
  attachRefund(req.refundPayments(),ret,settlement.actualRefund());
  ret.setRefundTotal(returned);
  returns.saveAndFlush(ret);
  replacement.setSubtotal(subtotal.setScale(2,RoundingMode.HALF_UP)); replacement.setGrandTotal(replacementValue);
  sales.saveAndFlush(replacement);

  for(PosReturnItem item:ret.getItems()) if("RESTOCK".equals(item.getRestockDisposition()))
   inventory.restockCustomerReturn(location.getId(),item.getSaleItem().getVariantId(),item.getQuantity(),
    "POS-EXCHANGE-RETURN-STOCK:"+key+":"+item.getSaleItem().getId(),actor.getId(),"Exchange return "+ret.getReturnNumber(),ret.getId());
  for(PosSaleItem item:replacement.getItems())
   inventory.sellStock(location.getId(),item.getVariantId(),item.getQuantity(),"POS-EXCHANGE-SALE-STOCK:"+key+":"+item.getVariantId(),
    actor.getId(),"Exchange replacement "+replacement.getInvoiceNumber(),"POS_SALE",replacement.getId());

  PosExchange exchange=new PosExchange(); exchange.setOriginalSale(original); exchange.setReturnRecord(ret);
  exchange.setReplacementSale(replacement); exchange.setShift(shift); exchange.setProcessedByUsername(username);
  exchange.setReason(req.reason().trim()); exchange.setReturnedValue(returned); exchange.setReplacementValue(replacementValue);
  exchange.setCreditApplied(settlement.creditApplied()); exchange.setActualRefund(settlement.actualRefund());
  exchange.setAmountCollected(settlement.amountCollected()); exchange.setIdempotencyKey(key); exchange.setRequestFingerprint(fp);
  exchanges.saveAndFlush(exchange);
  if(settlement.creditApplied().signum()>0) {
   PosExchangeCreditSettlement credit=new PosExchangeCreditSettlement(); credit.setExchange(exchange);
   credit.setReplacementSale(replacement); credit.setAmount(settlement.creditApplied()); credits.saveAndFlush(credit);
  }
  int n=0;
  for(PosPayment p:replacement.getPayments()) { if("CASH".equals(p.getPaymentMethod())) shifts.recordCashSale(shift,p.getAmount(),"POS-EXCHANGE-CASH-IN:"+key+":"+n,username,replacement.getId()); n++; }
  n=0;
  for(PosRefundPayment p:ret.getRefundPayments()) { if("CASH".equals(p.getPaymentMethod())) shifts.recordCashRefund(shift,p.getAmount(),"POS-EXCHANGE-CASH-OUT:"+key+":"+n,username,ret.getId()); n++; }
  return PosExchangeResponse.from(exchange);
 }

 private void attachCollection(List<PosPaymentRequest> rows,PosSale sale,BigDecimal due) {
  List<PosPaymentRequest> payments=rows==null?List.of():rows;
  if(due.signum()==0&&!payments.isEmpty()) throw new IllegalArgumentException("No collection payment is due");
  if(due.signum()>0&&payments.isEmpty()) throw new IllegalArgumentException("Collection payment is required");
  if(payments.size()>3) throw new IllegalArgumentException("At most three payment methods are allowed");
  Set<String> seen=new HashSet<>(); BigDecimal total=ZERO;
  for(PosPaymentRequest row:payments) {
   String method=normalizeMethod(row.getPaymentMethod()); if(!seen.add(method)) throw new IllegalArgumentException("Duplicate collection method");
   BigDecimal amount=money(row.getAmount(),"Collection amount"); if(amount.signum()<=0) throw new IllegalArgumentException("Collection amount must be positive");
   BigDecimal tendered=row.getTenderedAmount(),change=ZERO;
   if("CASH".equals(method)) { if(tendered==null||money(tendered,"Cash tendered").compareTo(amount)<0) throw new IllegalArgumentException("Cash tendered must cover collection"); tendered=money(tendered,"Cash tendered"); change=tendered.subtract(amount); }
   else if(tendered!=null) throw new IllegalArgumentException("Tendered amount is only valid for cash");
   String ref=row.getTransactionReference()==null?null:row.getTransactionReference().trim();
   if(!"CASH".equals(method)&&(ref==null||ref.isBlank())) throw new IllegalArgumentException("A transaction reference is required for UPI and card collections");
   if(ref!=null&&ref.length()>150) throw new IllegalArgumentException("Payment reference is too long");
   PosPayment p=new PosPayment(); p.setSale(sale); p.setPaymentMethod(method); p.setAmount(amount); p.setTenderedAmount(tendered); p.setChangeAmount(change); p.setTransactionReference(ref); sale.getPayments().add(p); total=total.add(amount);
  }
  if(total.compareTo(due)!=0) throw new IllegalArgumentException("Collection allocations must equal amount due");
 }
 private void attachRefund(List<PosRefundPaymentRequest> rows,PosReturn ret,BigDecimal due) {
  List<PosRefundPaymentRequest> payments=rows==null?List.of():rows;
  if(due.signum()==0&&!payments.isEmpty()) throw new IllegalArgumentException("No actual refund is due");
  if(due.signum()>0&&payments.isEmpty()) throw new IllegalArgumentException("Actual refund allocation is required");
  if(payments.size()>3) throw new IllegalArgumentException("At most three refund methods are allowed");
  Set<String> seen=new HashSet<>(); BigDecimal total=ZERO;
  for(PosRefundPaymentRequest row:payments) {
   String method=normalizeMethod(row.paymentMethod()); if(!seen.add(method)) throw new IllegalArgumentException("Duplicate refund method");
   BigDecimal amount=money(row.amount(),"Refund amount"); if(amount.signum()<=0) throw new IllegalArgumentException("Refund amount must be positive");
   String ref=row.transactionReference()==null?null:row.transactionReference().trim();
   if(!"CASH".equals(method)&&(ref==null||ref.isBlank())) throw new IllegalArgumentException("UPI/card refunds require a transaction reference");
   if(ref!=null&&ref.length()>150) throw new IllegalArgumentException("Refund transaction reference cannot exceed 150 characters");
   PosRefundPayment p=new PosRefundPayment(); p.setReturnRecord(ret); p.setPaymentMethod(method); p.setAmount(amount); p.setTransactionReference(ref); ret.getRefundPayments().add(p); total=total.add(amount);
  }
  if(total.compareTo(due)!=0) throw new IllegalArgumentException("Refund allocations must equal actual refund");
 }
 private PosExchangeResponse replay(PosExchange e,String fp) {
  if(!fp.equals(e.getRequestFingerprint())) throw new IllegalArgumentException("Exchange key was used with different details");
  return PosExchangeResponse.from(e);
 }
 private void validate(Long saleId,PosExchangeRequest r,String user) {
  if(saleId==null||saleId<=0||r==null||user==null||user.isBlank()) throw new IllegalArgumentException("Valid sale, request and authenticated staff are required");
  if(r.shiftId()==null||r.shiftId()<=0||r.reason()==null||r.reason().isBlank()||r.reason().trim().length()>500) throw new IllegalArgumentException("Valid shift and reason are required");
  if(r.idempotencyKey()==null||r.idempotencyKey().isBlank()||r.idempotencyKey().trim().length()>100) throw new IllegalArgumentException("Exchange key must contain 1 to 100 characters");
  if(r.returnItems()==null||r.returnItems().isEmpty()||r.returnItems().size()>100||r.returnItems().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("1 to 100 return lines are required");
  if(r.returnItems().stream().anyMatch(i->i.saleItemId()==null||i.saleItemId()<=0||i.quantity()==null||i.quantity()<=0)) throw new IllegalArgumentException("Return lines require valid sale item IDs and quantities");
  if(r.replacementItems()==null||r.replacementItems().isEmpty()||r.replacementItems().size()>100||r.replacementItems().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("1 to 100 replacement lines are required");
  if(r.replacementItems().stream().anyMatch(i->i.getProductId()==null||i.getProductId()<=0||i.getVariantId()==null||i.getVariantId()<=0||i.getQuantity()==null||i.getQuantity()<=0)) throw new IllegalArgumentException("Replacement lines require valid products, variants and quantities");
  if(r.collectionPayments()!=null&&r.collectionPayments().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Collection payment rows cannot be null");
  if(r.refundPayments()!=null&&r.refundPayments().stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Refund payment rows cannot be null");
 }
 private String normalizeDisposition(String s) { String v=s==null?"":s.trim().toUpperCase(Locale.ROOT); if(!Set.of("RESTOCK","DAMAGED","DISCARD").contains(v)) throw new IllegalArgumentException("Disposition must be RESTOCK, DAMAGED or DISCARD"); return v; }
 private String normalizeMethod(String s) { String v=s==null?"":s.trim().toUpperCase(Locale.ROOT); if(!METHODS.contains(v)) throw new IllegalArgumentException("Payment method must be CASH, UPI or CARD"); return v; }
 private BigDecimal money(BigDecimal v,String label) { if(v==null||v.scale()>2||v.precision()>12) throw new IllegalArgumentException(label+" must be DECIMAL(12,2)"); return v.setScale(2,RoundingMode.UNNECESSARY); }
 private String fingerprint(Long id,PosExchangeRequest r,String user) {
  try { byte[] b=mapper.writeValueAsBytes(Map.of("saleId",id,"username",user.trim().toLowerCase(Locale.ROOT),"request",r)); return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
  catch(JsonProcessingException|java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Unable to fingerprint exchange",e); }
 }
}
