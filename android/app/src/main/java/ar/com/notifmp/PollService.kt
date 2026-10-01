package ar.com.notifmp

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.Room
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class PollService : Service() {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var waitUntil: Long = 0
  companion object {
    const val CH = "transfers"
    const val CH_PERSIST = "service"
    fun start(ctx: Context, waitMin: Int = 0) {
      val i = Intent(ctx, PollService::class.java).putExtra("waitMin", waitMin)
      if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
  }
  override fun onBind(i: Intent?): IBinder? = null
  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CH, "Transferencias", NotificationManager.IMPORTANCE_HIGH))
    nm.createNotificationChannel(NotificationChannel(CH_PERSIST, "Servicio", NotificationManager.IMPORTANCE_LOW))
    startForeground(1, NotificationCompat.Builder(this, CH_PERSIST)
      .setContentTitle("Notif-MP activo").setContentText("Monitoreando transferencias")
      .setSmallIcon(android.R.drawable.ic_dialog_info).build())
  }
  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    val waitMin = i?.getIntExtra("waitMin", 0) ?: 0
    if (waitMin > 0) waitUntil = System.currentTimeMillis() + waitMin * 60_000
    scope.launch { loop() }
    return START_STICKY
  }
  private suspend fun loop() {
    val db = Room.databaseBuilder(this, AppDb::class.java, "notifmp.db").build()
    val tokens = TokenStore(this)
    while (true) {
      try {
        val prefs = applicationContext.ds.data
        val normal = 60; val wait = 15
        val waiting = System.currentTimeMillis() < waitUntil
        pollOnce(db, tokens)
        delay((if (waiting) wait else normal) * 1000L)
      } catch (_: Exception) { delay(30_000) }
    }
  }
  private suspend fun pollOnce(db: AppDb, tokens: TokenStore) {
    val token = tokens.access() ?: return
    if (tokens.needsRefresh()) {
      val rt = tokens.refresh() ?: return
      try {
        val r = proxy().refresh(mapOf("refresh_token" to rt))
        if (r.access_token != null) tokens.save(r.access_token, r.refresh_token ?: rt, r.expires_in ?: 15552000, r.user_id?.toString())
        else return
      } catch (_: Exception) { return }
    }
    val now = Date(); val begin = Date(now.time - 10 * 60_000)
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val url = "https://api.mercadopago.com/v1/payments/search?range=date_created&begin_date=${fmt.format(begin)}&end_date=${fmt.format(now)}&sort=date_created&criteria=desc&limit=50&status=approved"
    val http = OkHttpClient()
    val req = Request.Builder().url(url).header("Authorization", "Bearer ${tokens.access()}").build()
    val body = http.newCall(req).execute().use { it.body?.string() ?: return }
    val arr = JSONObject(body).optJSONArray("results") ?: return
    for (k in 0 until arr.length()) {
      val p = arr.getJSONObject(k)
      val isTransfer = p.optString("payment_method_id") == "cvu" || p.optString("operation_type") == "money_transfer"
      if (!isTransfer || p.optString("status") != "approved") continue
      val id = p.get("id").toString()
      val payer = p.optJSONObject("payer")
      val name = listOfNotNull(payer?.optString("first_name"), payer?.optString("last_name")).joinToString(" ").ifEmpty { payer?.optString("email") }
      val ins = db.movements().insert(Movement(id, p.optDouble("transaction_amount"), name.ifEmpty { null }, payer?.optString("email"), p.optString("date_approved"), p.optString("payment_method_id")))
      if (ins != -1L) notifyTransfer(p.optDouble("transaction_amount"))
    }
  }
  private fun notifyTransfer(amount: Double) {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val n = NotificationCompat.Builder(this, CH).setContentTitle("Transferencia recibida")
      .setContentText("$ ${String.format("%.2f", amount)} ARS").setSmallIcon(android.R.drawable.ic_dialog_info).build()
    nm.notify(amount.hashCode(), n)
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(c: Context, i: Intent) {
    if (i.action == Intent.ACTION_BOOT_COMPLETED) {
      CoroutineScope(Dispatchers.IO).launch {
        // reanuda solo si estaba activo
      }
    }
  }
}
