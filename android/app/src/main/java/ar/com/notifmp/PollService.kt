package ar.com.notifmp

import android.app.*
import android.content.*
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class PollService : Service() {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var turboUntil: Long = 0
  @Volatile var turboOn: Boolean = false
  companion object {
    const val CH = "transfers"
    const val CH_PERSIST = "service"
    const val TURBO_MS = 2000L
    var instance: PollService? = null
    fun start(ctx: Context) {
      val i = Intent(ctx, PollService::class.java)
      if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
    fun turbo(ctx: Context, minutes: Int = 10) {
      val i = Intent(ctx, PollService::class.java).putExtra("turbo", true).putExtra("turboMin", minutes)
      if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }
  }
  override fun onBind(i: Intent?): IBinder? = null
  private var loopJob: Job? = null
  override fun onCreate() {
    super.onCreate()
    instance = this
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel(CH, "Transferencias", NotificationManager.IMPORTANCE_HIGH))
    nm.createNotificationChannel(NotificationChannel(CH_PERSIST, "Servicio", NotificationManager.IMPORTANCE_LOW))
    updateNotif("Monitoreando transferencias")
  }
  private fun updateNotif(text: String) {
    startForeground(1, NotificationCompat.Builder(this, CH_PERSIST)
      .setContentTitle("Notif-MP activo").setContentText(text)
      .setSmallIcon(android.R.drawable.ic_dialog_info).build())
  }
  override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
    if (i?.getBooleanExtra("turbo", false) == true) {
      val mins = i.getIntExtra("turboMin", 10).coerceIn(1, 60)
      turboOn = true
      turboUntil = System.currentTimeMillis() + mins * 60_000
      updateNotif("Turbo 2s activo")
    }
    if (loopJob == null) loopJob = scope.launch { loop() }
    return START_STICKY
  }
  private suspend fun loop() {
    val db = Room.databaseBuilder(this, AppDb::class.java, "notifmp.db").build()
    val tokens = TokenStore(this)
    while (true) {
      try {
        val normal = applicationContext.ds.data.first()[Keys.NORMAL_SEC]?.coerceIn(10, 60) ?: 60
        if (turboOn && System.currentTimeMillis() > turboUntil) {
          turboOn = false
          updateNotif("Monitoreando transferencias")
        }
        val turbo = turboOn && System.currentTimeMillis() < turboUntil
        val gotNew = pollOnce(db, tokens)
        if (turbo && gotNew) {
          turboOn = false
          updateNotif("Monitoreando transferencias")
        }
        if (turbo) delay(TURBO_MS) else delay(normal * 1000L)
      } catch (_: Exception) { delay(10_000) }
    }
  }
  private suspend fun pollOnce(db: AppDb, tokens: TokenStore): Boolean {
    tokens.access() ?: return false
    if (tokens.needsRefresh()) {
      val rt = tokens.refresh() ?: return false
      try {
        val r = proxy().refresh(mapOf("refresh_token" to rt))
        if (r.access_token != null) tokens.save(r.access_token, r.refresh_token ?: rt, r.expires_in ?: 15552000, r.user_id?.toString())
        else return false
      } catch (_: Exception) { return false }
    }
    val now = Date(); val begin = Date(now.time - 10 * 60_000)
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val url = "https://api.mercadopago.com/v1/payments/search?range=date_created&begin_date=${fmt.format(begin)}&end_date=${fmt.format(now)}&sort=date_created&criteria=desc&limit=50&status=approved"
    val http = OkHttpClient()
    val req = Request.Builder().url(url).header("Authorization", "Bearer ${tokens.access()}").build()
    val code: Int; val body: String
    try {
      http.newCall(req).execute().use { resp -> code = resp.code; body = resp.body?.string() ?: return false }
    } catch (_: Exception) { return false }
    if (code == 429) { delay(10_000); return false }
    if (code !in 200..299) return false
    val arr = JSONObject(body).optJSONArray("results") ?: return false
    var found = false
    for (k in 0 until arr.length()) {
      val p = arr.getJSONObject(k)
      val isTransfer = p.optString("payment_method_id") == "cvu" || p.optString("operation_type") == "money_transfer"
      if (!isTransfer || p.optString("status") != "approved") continue
      val id = p.get("id").toString()
      val payer = p.optJSONObject("payer")
      val name = listOfNotNull(payer?.optString("first_name"), payer?.optString("last_name")).joinToString(" ").ifEmpty { payer?.optString("email").orEmpty() }
      val ins = db.movements().insert(Movement(id, p.optDouble("transaction_amount"), name.takeIf { it.isNotBlank() }, payer?.optString("email"), p.optString("date_approved"), p.optString("payment_method_id")))
      if (ins != -1L) { notifyTransfer(p.optDouble("transaction_amount")); found = true }
    }
    return found
  }
  private fun notifyTransfer(amount: Double) {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val n = NotificationCompat.Builder(this, CH).setContentTitle("Transferencia recibida")
      .setContentText("$ ${String.format("%.2f", amount)} ARS").setSmallIcon(android.R.drawable.ic_dialog_info).build()
    nm.notify(amount.hashCode(), n)
  }
  override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }
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
