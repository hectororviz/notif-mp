package ar.com.notifmp

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object PremiumManager : PurchasesUpdatedListener {
  const val PRODUCT_ID = "premium_no_ads"
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var client: BillingClient? = null
  private var details: ProductDetails? = null

  val isPremium = MutableStateFlow(false)
  val priceText = MutableStateFlow<String?>(null)
  val statusMsg = MutableStateFlow<String?>(null)

  fun init(ctx: Context) {
    if (client != null) { refresh(); return }
    client = BillingClient.newBuilder(ctx.applicationContext)
      .setListener(this)
      .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
      .build()
    connect { refresh() }
  }

  private fun connect(done: () -> Unit = {}) {
    val c = client ?: return
    if (c.isReady) { done(); return }
    c.startConnection(object : BillingClientStateListener {
      override fun onBillingSetupFinished(r: BillingResult) {
        if (r.responseCode == BillingClient.BillingResponseCode.OK) {
          scope.launch { queryProduct() }
          done()
        } else {
          statusMsg.tryEmit("Billing no disponible (${r.responseCode})")
        }
      }
      override fun onBillingServiceDisconnected() {}
    })
  }

  private suspend fun queryProduct() {
    val c = client ?: return
    val params = QueryProductDetailsParams.newBuilder()
      .setProductList(
        listOf(
          QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        )
      ).build()
    try {
      val res = c.queryProductDetails(params)
      val pd = res.productDetailsList?.firstOrNull { it.productId == PRODUCT_ID }
      details = pd
      pd?.oneTimePurchaseOfferDetails?.formattedPrice?.let { priceText.tryEmit(it) }
    } catch (_: Exception) {}
  }

  fun refresh() {
    connect {
      scope.launch {
        try {
          queryProduct()
          checkOwned()
        } catch (_: Exception) {}
      }
    }
  }

  fun restore() {
    statusMsg.tryEmit("Consultando compras…")
    refresh()
  }

  private suspend fun checkOwned() {
    val c = client ?: return
    val owned = try {
      val res = c.queryPurchasesAsync(
        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
      )
      res.purchasesList.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
    } catch (_: Exception) { isPremium.value }
    if (owned) {
      acknowledgeIfNeeded()
      if (!isPremium.value) statusMsg.tryEmit("Premium restaurado ✓")
    }
    isPremium.tryEmit(owned)
  }

  private suspend fun acknowledgeIfNeeded() {
    val c = client ?: return
    try {
      val res = c.queryPurchasesAsync(
        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
      )
      for (p in res.purchasesList) {
        if (p.products.contains(PRODUCT_ID) && p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged) {
          c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        }
      }
    } catch (_: Exception) {}
  }

  override fun onPurchasesUpdated(r: BillingResult, purchases: List<Purchase>?) {
    if (r.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
      scope.launch {
        val ok = purchases.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (ok) {
          acknowledgeIfNeeded()
          isPremium.tryEmit(true)
          statusMsg.tryEmit("¡Premium activado! Sin publicidad ✓")
        }
      }
    } else if (r.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
      statusMsg.tryEmit("Compra cancelada")
    } else if (r.responseCode != BillingClient.BillingResponseCode.OK) {
      statusMsg.tryEmit("Error de compra (${r.responseCode})")
    }
  }

  fun launchBuy(act: Activity) {
    val pd = details
    if (pd == null) {
      statusMsg.tryEmit("Cargando producto… reintentá")
      refresh()
      return
    }
    val c = client
    if (c == null || !c.isReady) {
      statusMsg.tryEmit("Conectando con Play… reintentá")
      refresh()
      return
    }
    val params = BillingFlowParams.newBuilder()
      .setProductDetailsParamsList(
        listOf(
          BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(pd)
            .build()
        )
      ).build()
    c.launchBillingFlow(act, params)
  }
}
