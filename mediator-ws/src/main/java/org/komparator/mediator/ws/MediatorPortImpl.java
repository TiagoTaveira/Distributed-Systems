package org.komparator.mediator.ws;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.jws.WebService;

import org.komparator.supplier.ws.BadProductId_Exception;
import org.komparator.supplier.ws.BadQuantity;
import org.komparator.supplier.ws.BadQuantity_Exception;
import org.komparator.supplier.ws.BadText_Exception;
import org.komparator.supplier.ws.InsufficientQuantity;
import org.komparator.supplier.ws.InsufficientQuantity_Exception;
import org.komparator.supplier.ws.ProductView;
import org.komparator.supplier.ws.cli.SupplierClient;
import org.komparator.supplier.ws.cli.SupplierClientException;

import pt.ulisboa.tecnico.sdis.ws.cli.CreditCardClient;
import pt.ulisboa.tecnico.sdis.ws.cli.CreditCardClientException;
import pt.ulisboa.tecnico.sdis.ws.uddi.UDDINaming;
import pt.ulisboa.tecnico.sdis.ws.uddi.UDDINamingException;
import pt.ulisboa.tecnico.sdis.ws.uddi.UDDIRecord;


// TODO annotate to bind with WSDL

@WebService(
		endpointInterface = "org.komparator.mediator.ws.MediatorPortType", 
		wsdlLocation = "mediator.1_0.wsdl", 
		name = "MediatorWebService", 
		portName = "MediatorPort", 
		targetNamespace = "http://ws.mediator.komparator.org/", 
		serviceName = "MediatorService"
)

public class MediatorPortImpl implements MediatorPortType {

	// end point manager
	private MediatorEndpointManager endpointManager;
	private static long idCounter = 0;
	private Map<String,CartView> cartList = new ConcurrentHashMap<String,CartView>();
	private List<ShoppingResultView> shopRecs = new CopyOnWriteArrayList<ShoppingResultView>();
	private List<Timestamp> timestampList = new ArrayList<Timestamp>();

	public MediatorPortImpl(MediatorEndpointManager endpointManager) {
		this.endpointManager = endpointManager;
	}


	// Main operations -------------------------------------------------------

	//FIXED
	@Override
	public List<ItemView> getItems(String productId) throws InvalidItemId_Exception {
		//check arg
		if (productId == null)
			throwInvalidItemId("Product identifier cannot be null!");
		productId = productId.trim();
		if (productId.length() == 0)
			throwInvalidItemId("Product identifier cannot be empty or whitespace!");
		List<ItemView> items = new ArrayList<ItemView>();
		UDDINaming uddi = endpointManager.getUddiNaming();
		try {
			Collection<UDDIRecord> supplierRecords = uddi.listRecords("T36_Supplier%");
			
			for(UDDIRecord record: supplierRecords){
				try {
				SupplierClient clnt = new SupplierClient(record.getUrl());
				ProductView pv = clnt.getProduct(productId);
				if(pv != null){
						items.add(newItemView(pv, record.getOrgName()));
				}
			} catch (SupplierClientException e) {
				continue;
			}
			}

			Collections.sort(items, (item1, item2) -> item1.getPrice() - item2.getPrice());

			} catch (UDDINamingException e) {
			} catch (BadProductId_Exception e) {
				throwInvalidItemId("Invalid Id");
			}
		return items;
	}

	
//FIXED
	@Override
	public List<ItemView> searchItems(String descText) throws InvalidText_Exception {
		if( descText==null) {
			throwInvalidText("product description cannot be null!");
		}
		descText = descText.trim();
		if (descText.length() == 0) {
			throwInvalidText("Product description cannot be empty or whitespace!"); 
		}

		UDDINaming uddi = endpointManager.getUddiNaming();
		List<ItemView> items = new ArrayList<ItemView>();		
		try {
			Collection<UDDIRecord> supplierRecords = uddi.listRecords("T36_Supplier%");

			for(UDDIRecord record: supplierRecords){
				SupplierClient clnt = new SupplierClient(record.getUrl());
				List<ProductView> aux1 = clnt.searchProducts(descText);
				for(ProductView p: aux1){
					if(p != null){
						items.add(newItemView(p, record.getOrgName()));
					}
				}
			}
		orderItemsList(items);
		} catch (UDDINamingException e) {
			e.printStackTrace();
		} catch (SupplierClientException e) {
			e.printStackTrace();
		} catch (BadText_Exception e) {
			throwInvalidText("Invalid Text");
		}
		return items;
	}

	@Override
	public ShoppingResultView buyCart(String cartId, String creditCardNr)
			throws EmptyCart_Exception, InvalidCartId_Exception, InvalidCreditCard_Exception {
		
		if (cartId == null)
			throwInvalidCartId("Cart identifier cannot be null!");
		cartId = cartId.trim();
		if (cartId.length() == 0)
			throwInvalidCartId("Cart identifier cannot be empty or whitespace!");
		if(cartList.get(cartId) == null)
			throwInvalidCartId("Requiered cart doesen't exists.");
		if(cartList.get(cartId).getItems().size() == 0)
			throwEmptyCart("The required cart is empty.");

		UDDINaming uddi = endpointManager.getUddiNaming();
		ShoppingResultView shopRes = new ShoppingResultView();	
		try {
			String cc = uddi.lookup("CreditCard");
			CreditCardClient clnt = new CreditCardClient(cc);
			if(!clnt.validateNumber(creditCardNr)){
				throwInvalidCreditCard("Invalid credit card number!");
			}

		CartView cart = cartList.get(cartId);
		synchronized (cart){
		int numberOfItems = cart.getItems().size();
		shopRes.setResult(Result.EMPTY);
		int total = 0;


		List<CartItemView> items = new ArrayList<CartItemView>();
		items.addAll(cart.getItems());
		for(CartItemView item: items){
			try {
			String sURL = uddi.lookup(item.getItem().getItemId().getSupplierId());
			SupplierClient clint = new SupplierClient(sURL);
			clint.buyProduct(item.getItem().getItemId().getProductId(), item.getQuantity());
			
			//Add to purchased list
			shopRes.getPurchasedItems().add(item);
			//remove from cart
			cart.getItems().remove(item);
			//update total price
			total+=item.getItem().getPrice() * item.getQuantity();

			} catch (BadProductId_Exception | BadQuantity_Exception | 
					InsufficientQuantity_Exception | SupplierClientException | UDDINamingException e) {
				shopRes.getDroppedItems().add(item);
				continue;
			}
		} 

		int bought = shopRes.getPurchasedItems().size();
		if(bought > 0 && bought < numberOfItems){
			shopRes.setResult(Result.PARTIAL);
		}
		else if(bought == numberOfItems){ 
			shopRes.setResult(Result.COMPLETE);}
		
		shopRes.setId(createID());
		shopRes.setTotalPrice(total);
		}

		} catch (CreditCardClientException e) {
			e.printStackTrace();
		} catch (UDDINamingException e){
			e.printStackTrace();
		}
		return shopRes;
	}

//FIXED
	@Override
	public void addToCart(String cartId, ItemIdView itemId, int itemQty) throws InvalidCartId_Exception,
			InvalidItemId_Exception, InvalidQuantity_Exception, NotEnoughItems_Exception {
		//cartId 
		if (cartId == null)
			throwInvalidCartId("Cart identifier cannot be null!");
		cartId = cartId.trim();
		if (cartId.length() == 0)
			throwInvalidCartId("Cart identifier cannot be empty or whitespace!");
		//Quantity
		if (itemQty <= 0)
			throwInvalidQuantity("Quantity cannot be null or negative");
		//ItemId
		String id = itemId.getProductId();
		String sId = itemId.getSupplierId();
		if (id == null || sId == null)
			throwInvalidItemId("Item identifier cannot be null!");
		id = id.trim();
		sId = sId.trim();
		if (id.length() == 0 || sId.length() == 0)
			throwInvalidItemId("Item identifier cannot be empty or whitespace!");

		CartView cv = cartList.get(cartId);
		int total = 0;
		Boolean exists = false;

		try {
			SupplierClient sup = new SupplierClient(endpointManager.getUddiNaming().lookup(itemId.getSupplierId()));
			
			ProductView prod = sup.getProduct(itemId.getProductId());
			if(prod == null){
				throwInvalidItemId("Item does not exists!");
			}

		if(cv == null){
			cv = new CartView();
			cv.setCartId(cartId);
			cartList.put(cartId, cv); 	
		}

		synchronized (cv) {
			for(CartItemView i: cv.getItems()){
				if(i.getItem().getItemId().getProductId().equals(itemId.getProductId()) && 
				i.getItem().getItemId().getSupplierId().equals(itemId.getSupplierId())) {	
					boolean alreadyExists = true;
					total = i.getQuantity() + itemQty;
					try {
					if(total > sup.getProduct(itemId.getProductId()).getQuantity()){
						throwNotEnoughItems("Not enough quantity.");
					}
					} catch (BadProductId_Exception e) {
					throwInvalidItemId("itemId is invalid!");
					}
					i.setQuantity(total);
			}
		}

		if(exists == false){
			ProductView pv = newProductView(itemId);
			ItemView iv = newItemView(pv,itemId.getSupplierId());
			CartItemView cari = new CartItemView();
			cari.setItem(iv);

			try {
				if(itemQty > sup.getProduct(itemId.getProductId()).getQuantity()){
					throwNotEnoughItems("Not enough quantity!");
				}
			} catch (BadProductId_Exception e) {
				throwInvalidItemId("invalid item id!");
			}			
			cari.setQuantity(itemQty);
			cv.getItems().add(cari);
		}
	}
	
	} catch (SupplierClientException e) {
			throwInvalidItemId("Invalid supplier.");
		} catch (UDDINamingException e) {
			throwInvalidItemId("invalid Supplier");
		} catch (BadProductId_Exception e1) {
			throwInvalidItemId("Invalid item!");
		}
	}



   
	// Auxiliary operations --------------------------------------------------	
	
	//FIXED
	public List<SupplierClient> getClients() {
		UDDINaming uddi = endpointManager.getUddiNaming();
		List<SupplierClient> clients = new ArrayList<SupplierClient>();
		try {
			Collection<UDDIRecord> supplierRecords = uddi.listRecords("T36_Supplier%");
			
			for(UDDIRecord record: supplierRecords){
				try {
				SupplierClient clnt = new SupplierClient(record.getUrl());
				clients.add(clnt);
			} catch (SupplierClientException e) {
				continue;
			}
		}
		} catch (UDDINamingException e) {
			e.printStackTrace();
		}
		return clients;	
		}

//FIXED
    @Override
	public void clear() {
		UDDINaming uddi = endpointManager.getUddiNaming();
		
		try {
			Collection<UDDIRecord> supplierRecords = uddi.listRecords("T36_Supplier%");
			for(UDDIRecord record: supplierRecords){
				SupplierClient clnt = new SupplierClient(record.getUrl());
				clnt.clear();}
			
			idCounter = 0;
			cartList.clear();
			shopRecs.clear();

		} catch (UDDINamingException e) {
			e.printStackTrace();
		} catch (SupplierClientException e) {
			e.printStackTrace();
		}					
	}

//FIXED
	@Override
	public List<ShoppingResultView> shopHistory() {
		ShoppingResultView shopRes = new ShoppingResultView();
		shopRes.setId("history");
		Collections.sort(shopRecs, (item1, item2) -> Integer.compare(Integer.parseInt(item2.getId()), 
				Integer.parseInt(item1.getId())));
		return shopRecs;
	}

//FIXED	
	@Override
	public String ping(String arg0) {

		UDDINaming uddi = endpointManager.getUddiNaming();
		try {
			Collection<UDDIRecord> supplierRecords = uddi.listRecords("T35_Supplier%");
			String finalString = "";
			for(UDDIRecord record: supplierRecords){
				SupplierClient clnt = new SupplierClient(record.getUrl());
				String aux1 = clnt.ping("mediator");
								
				finalString = finalString + "Input from " + record.getOrgName() + ": " + aux1 + "\n";	
			}
		
		return finalString;	
			
		} catch (UDDINamingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SupplierClientException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	
	}

//FIXED
	@Override
	public List<CartView> listCarts() {
		List<CartView> carts = new ArrayList<CartView>(cartList.values());
		return carts;
	}


//FIXED
	private static void orderItemsList(List<ItemView> items) {

	    Collections.sort(items, new Comparator<ItemView>() {

	        public int compare(ItemView item1, ItemView item2) {

	            String id1 = item1.getItemId().getProductId();
	            String id2 = item2.getItemId().getProductId();
	            int aux = id1.compareTo(id2);

	            if (aux == 0) {
					Integer price1 = item1.getPrice();
	               Integer price2 = item2.getPrice();
	               return price1.compareTo(price2);
				}
				else {
					return aux;
				}
	    }});
	}
//FIXED
	private static synchronized String createID(){
		return String.valueOf(idCounter++);
	}


	// View helpers -------------------------------------------------------
	
	//FIXED
   private ItemView newItemView(ProductView product, String supplier) {
		ItemIdView iv = new ItemIdView();
		iv.setProductId(product.getId());
		iv.setSupplierId(supplier);
		
		ItemView view = new ItemView();		
		view.setDesc(product.getDesc());
		view.setItemId(iv);
		view.setPrice(product.getPrice());
		return view;
	}
	
//FIXED
	private ProductView newProductView(ItemIdView item){
		UDDINaming uddi = endpointManager.getUddiNaming();		
		UDDIRecord rec; //changed
		try {
			rec = uddi.lookupRecord(item.getSupplierId());		
			SupplierClient clnt = new SupplierClient(rec.getUrl());
			ProductView pv = clnt.getProduct(item.getProductId());
			return pv;
		} catch (UDDINamingException e) {
		} catch (SupplierClientException e) {
		} catch (BadProductId_Exception e) {
		}
		return null;
	}

    
	// Exception helpers -----------------------------------------------------

    private void throwInvalidItemId(final String message) throws InvalidItemId_Exception {
		InvalidItemId faultInfo = new InvalidItemId();
		faultInfo.message = message;
		throw new InvalidItemId_Exception(message, faultInfo);
	}

	private void throwInvalidText(final String message) throws InvalidText_Exception {
		InvalidText faultInfo = new InvalidText();
		faultInfo.message = message;
		throw new InvalidText_Exception(message, faultInfo);
	}

	private void throwInvalidCartId(final String message) throws InvalidCartId_Exception {
		InvalidCartId faultInfo = new InvalidCartId();
		faultInfo.message = message;
		throw new InvalidCartId_Exception(message, faultInfo);
	}

	private void throwEmptyCart(final String message) throws EmptyCart_Exception {
		EmptyCart faultInfo = new EmptyCart();
		faultInfo.message = message;
		throw new EmptyCart_Exception(message, faultInfo);
	}

	private void throwInvalidQuantity(final String message) throws InvalidQuantity_Exception {
		InvalidQuantity faultInfo = new InvalidQuantity();
		faultInfo.message = message;
		throw new InvalidQuantity_Exception(message, faultInfo);
	}

	private void throwInvalidCreditCard(final String message) throws InvalidCreditCard_Exception {
		InvalidCreditCard faultInfo = new InvalidCreditCard();
		faultInfo.message = message;
		throw new InvalidCreditCard_Exception(message, faultInfo);
	}

	private void throwNotEnoughItems(final String message) throws NotEnoughItems_Exception {
		NotEnoughItems faultInfo = new NotEnoughItems();
		faultInfo.message = message;
		throw new NotEnoughItems_Exception(message, faultInfo);
	}


	@Override
	public void imAlive() {
		if(endpointManager.getStateServer()){
			Timestamp timestamp = new Timestamp(System.currentTimeMillis());
			timestampList.add(timestamp);
			
		}else {
			return;
		}
		
	}

}