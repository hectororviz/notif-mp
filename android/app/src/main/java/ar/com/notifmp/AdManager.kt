package ar.com.notifmp

import android.content.Context
import com.google.android.gms.ads.MobileAds

object AdManager {
  @Volatile private var adsInit = false

  fun initIfNeeded(ctx: Context) {
    if (adsInit) return
    try {
      MobileAds.initialize(ctx.applicationContext)
    } catch (_: Exception) {}
    adsInit = true
  }
}
