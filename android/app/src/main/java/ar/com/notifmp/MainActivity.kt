package ar.com.notifmp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
import com.google.android.gms.ads.*
import com.google.android.ump.UserMessagingPlatform
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) {}
    MobileAds.initialize(this)
    handleDeepLink(intent)
    setContent { Root() }
  }
  override fun onNewIntent(i: Intent) { super.onNewIntent(i); handleDeepLink(i) }
  private fun handleDeepLink(i: Intent) {
    val d = i.data ?: return
    if (d.toString().contains("oauth/callback")) {
      val code = d.getQueryParameter("code") ?: return
      val state = d.getQueryParameter("state") ?: return
      val prefs = getSharedPreferences("pkce", 0)
      if (prefs.getString("last_state", "") != state) return
      val verifier = prefs.getString("v_$state", null) ?: return
      Thread {
        try {
          val r = kotlinx.coroutines.runBlocking {
            proxy().exchange(mapOf("code" to code, "code_verifier" to verifier, "redirect_uri" to BuildConfig.REDIRECT_URI))
          }
          if (r.access_token != null)
            TokenStore(this).save(r.access_token, r.refresh_token, r.expires_in ?: 15552000, r.user_id?.toString())
        } catch (_: Exception) {}
      }.start()
    }
  }
}

@Composable
fun Root() {
  val ctx = LocalContext.current
  var dark by remember { mutableStateOf(false) }
  var keepOn by remember { mutableStateOf(false) }
  val act = ctx as? Activity
  LaunchedEffect(keepOn) {
    if (keepOn) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
      AndroidView(factory = { c ->
        AdView(c).apply {
          setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(c, 360))
          adUnitId = BuildConfig.ADMOB_BANNER_ID
          loadAd(AdRequest.Builder().build())
        }
      }, modifier = Modifier.fillMaxWidth().height(60.dp))
    }) { pad ->
      Column(Modifier.padding(pad)) {
        TabRow(selectedTabIndex = tab) {
          listOf("En vivo", "Movimientos", "Configuración").forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
          }
        }
        when (tab) {
          0 -> Live()
          1 -> Movs()
          2 -> Config(dark, { dark = it }, keepOn, { keepOn = it })
        }
      }
    }
  }
}

@Composable
fun Live() {
  val ctx = LocalContext.current
  var list by remember { mutableStateOf<List<Movement>>(emptyList()) }
  LaunchedEffect(Unit) {
    val db = Room.databaseBuilder(ctx, AppDb::class.java, "notifmp.db").build()
    list = db.movements().last(5)
  }
  val top = list.firstOrNull()
  val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") } }
  Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(if (top != null) "$ ${"%.2f".format(top.amount)}" else "—", style = MaterialTheme.typography.displayLarge)
    Text(if (top != null) "${top.payer ?: "Transferencia"} · ${fmt.format(Date(top.notifiedAt))}" else "Sin transferencias aún")
    Spacer(Modifier.height(16.dp))
    Text("Últimas 5", style = MaterialTheme.typography.titleMedium)
    LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
      items(list.take(5)) { m ->
        Text("$ ${"%.2f".format(m.amount)} · ${fmt.format(Date(m.notifiedAt))}", modifier = Modifier.padding(4.dp))
      }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = { PollService.start(ctx, 10) }) { Text("Modo espera (10 min)") }
  }
}

@Composable
fun Movs() {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var list by remember { mutableStateOf<List<Movement>>(emptyList()) }
  var sel by remember { mutableStateOf<Movement?>(null) }
  var fromD by remember { mutableStateOf("") }
  var toD by remember { mutableStateOf("") }
  val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
    if (uri != null) scope.launch {
      ctx.contentResolver.openOutputStream(uri)?.use { it.write(movementsToCsv(list).toByteArray()) }
    }
  }
  val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
    if (uri != null) scope.launch { movementsToXlsx(ctx, uri, list) }
  }
  LaunchedEffect(Unit) {
    val db = Room.databaseBuilder(ctx, AppDb::class.java, "notifmp.db").build()
    list = db.movements().last(200)
  }
  val df = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") } }
  val today = df.format(Date())
  val yest = df.format(Date(System.currentTimeMillis() - 86400000))
  fun dayOf(s: String) = list.filter { df.format(Date(it.notifiedAt)) == s }.sumOf { it.amount }
  Column(Modifier.fillMaxSize().padding(16.dp)) {
    Text("Total hoy: $ ${"%.2f".format(dayOf(today))} ARS")
    Text("Total ayer: $ ${"%.2f".format(dayOf(yest))} ARS")
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(fromD, { fromD = it }, label = { Text("Desde (aaaa-mm-dd)") }, modifier = Modifier.weight(1f))
      OutlinedTextField(toD, { toD = it }, label = { Text("Hasta") }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
      Button(onClick = {
        scope.launch {
          val db = Room.databaseBuilder(ctx, AppDb::class.java, "notifmp.db").build()
          list = try {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(fromD)?.time ?: 0
            val t = (SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(toD)?.time ?: System.currentTimeMillis()) + 86400000
            db.movements().range(f, t)
          } catch (_: Exception) { db.movements().last(200) }
        }
      }) { Text("Filtrar") }
      Button(onClick = { csvLauncher.launch("notif-mp.csv") }) { Text("CSV") }
      Button(onClick = { xlsxLauncher.launch("notif-mp.xlsx") }) { Text("Excel") }
    }
    LazyColumn {
      items(list) { m ->
        val hf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") }
        TextButton(onClick = { sel = m }) { Text("${hf.format(Date(m.notifiedAt))} · $ ${"%.2f".format(m.amount)}") }
      }
    }
  }
  sel?.let { m ->
    AlertDialog(onDismissRequest = { sel = null }, confirmButton = {
      TextButton(onClick = { sel = null }) { Text("Cerrar") }
    }, title = { Text("$ ${"%.2f".format(m.amount)} ARS") },
      text = { Text("Fecha: ${Date(m.notifiedAt)}\nPagador: ${m.payer ?: "-"}\nEmail: ${m.email ?: "-"}\nPayment ID: ${m.mpId}\nTipo: ${m.type ?: "-"}\nAprobada: ${m.dateApproved ?: "-"}") })
  }
}

@Composable
fun Config(dark: Boolean, onDark: (Boolean) -> Unit, keepOn: Boolean, onKeep: (Boolean) -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var linked by remember { mutableStateOf(TokenStore(ctx).linked()) }
  var sound by remember { mutableStateOf(true) }
  var ttsOn by remember { mutableStateOf(true) }
  var normal by remember { mutableStateOf("60") }
  var wait by remember { mutableStateOf("15") }
  var authUrl by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(linked) { if (!linked) linked = TokenStore(ctx).linked() }
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("OAuth MP: " + if (linked) "Vinculado ✓" else "No vinculado")
    if (!linked) {
      Button(onClick = {
        val v = Pkce.verifier()
        val state = UUID.randomUUID().toString()
        ctx.getSharedPreferences("pkce", 0).edit().putString("v_$state", v).putString("last_state", state).apply()
        val url = authUrl(Pkce.challenge(v), state)
        authUrl = url
        openAuth(ctx, url)
      }) { Text("Vincular con Mercado Pago") }
      authUrl?.let { u ->
        Text("QR de vinculación (escaneable desde otro equipo):")
        Image(qrBitmap(u, 420).asImageBitmap(), contentDescription = "QR vincular", modifier = Modifier.size(210.dp))
      }
    } else {
      Button(onClick = { scope.launch { TokenStore(ctx).clear(); linked = false } }) { Text("Desvincular") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Modo oscuro"); Spacer(Modifier.width(8.dp)); Switch(dark, onDark) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Sonido"); Spacer(Modifier.width(8.dp)); Switch(sound, { sound = it }) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Monto hablado (TTS)"); Spacer(Modifier.width(8.dp)); Switch(ttsOn, { ttsOn = it }) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("No apagar pantalla"); Spacer(Modifier.width(8.dp)); Switch(keepOn, onKeep) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(normal, { normal = it }, label = { Text("Normal (s)") }, modifier = Modifier.weight(1f))
      OutlinedTextField(wait, { wait = it }, label = { Text("Espera (s)") }, modifier = Modifier.weight(1f))
    }
    Button(onClick = {
      scope.launch {
        ctx.ds.edit { it[Keys.NORMAL_SEC] = normal.toIntOrNull() ?: 60; it[Keys.WAIT_SEC] = wait.toIntOrNull() ?: 15; it[Keys.SOUND] = sound; it[Keys.TTS] = ttsOn; it[Keys.SERVICE_ON] = true }
        PollService.start(ctx)
      }
    }) { Text("Guardar e iniciar") }
  }
}

