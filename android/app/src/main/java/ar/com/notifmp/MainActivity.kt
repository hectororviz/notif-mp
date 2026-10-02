package ar.com.notifmp

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.google.android.gms.ads.*
import com.google.android.ump.UserMessagingPlatform
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
    if (!d.toString().contains("oauth/callback")) return
    val code = d.getQueryParameter("code")
    val state = d.getQueryParameter("state")
    OauthLog.add("callback", "url=${d.toString().take(120)}")
    Log.d("NotifMP", "callback code=${code?.take(8)} state=$state")
    if (code.isNullOrEmpty() || state.isNullOrEmpty()) {
      OauthLog.add("callback", "FAIL sin code/state")
      Toast.makeText(this, "Callback sin code/state", Toast.LENGTH_LONG).show()
      return
    }
    val prefs = getSharedPreferences("pkce", 0)
    if (prefs.getString("last_state", "") != state) {
      OauthLog.add("state", "FAIL no coincide")
      Toast.makeText(this, "State no coincide (reintentá vincular)", Toast.LENGTH_LONG).show()
      return
    }
    OauthLog.add("state", "OK")
    val verifier = prefs.getString("v_$state", null)
    if (verifier.isNullOrEmpty()) {
      OauthLog.add("pkce", "FAIL sin verifier (sesión expirada)")
      Toast.makeText(this, "Sesión expirada, reintentá vincular", Toast.LENGTH_LONG).show()
      return
    }
    Toast.makeText(this, "Código recibido, canjeando…", Toast.LENGTH_SHORT).show()
    OauthLog.add("exchange", "POST ${BuildConfig.PROXY_URL}/oauth/exchange")
    Thread {
      try {
        val r = kotlinx.coroutines.runBlocking {
          proxy().exchange(mapOf("code" to code, "code_verifier" to verifier, "redirect_uri" to BuildConfig.REDIRECT_URI))
        }
        OauthLog.add("exchange", "HTTP OK access=${if (r.access_token != null) "sí" else "no"} expires=${r.expires_in} user=${r.user_id}")
        if (r.access_token != null) {
          try {
            TokenStore(this).save(r.access_token, r.refresh_token, r.expires_in ?: 15552000, r.user_id?.toString())
            OauthLog.add("store", "OK guardado")
          } catch (e: Exception) {
            OauthLog.add("store", "FAIL ${e.javaClass.simpleName}: ${e.message}")
          }
          runOnUiThread { Toast.makeText(this, "¡Vinculado con Mercado Pago! ✓", Toast.LENGTH_LONG).show() }
        } else {
          runOnUiThread { Toast.makeText(this, "El proxy no devolvió token", Toast.LENGTH_LONG).show() }
        }
      } catch (e: Exception) {
        OauthLog.add("exchange", "FAIL ${e.javaClass.simpleName}: ${e.message}")
        Log.e("NotifMP", "exchange fail", e)
        runOnUiThread { Toast.makeText(this, "Error canjeando: ${e.message}", Toast.LENGTH_LONG).show() }
      }
    }.start()
  }
}

@Composable
fun appBtnColors(btnColor: Int) = ButtonDefaults.buttonColors(containerColor = Color(btnColor))

@Composable
fun Root() {
  val ctx = LocalContext.current
  var dark by remember { mutableStateOf(false) }
  var keepOn by remember { mutableStateOf(false) }
  val act = ctx as? Activity
  val scope = rememberCoroutineScope()
  val onboardDone by ctx.ds.data.map { it[Keys.ONBOARD_DONE] == true }.collectAsState(initial = true)
  var showHelp by remember { mutableStateOf(false) }
  val btnColor by ctx.ds.data.map { it[Keys.BTN_COLOR] ?: BTN_PRESET[0] }.collectAsState(initial = BTN_PRESET[0])
  LaunchedEffect(keepOn) {
    if (keepOn) act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    else act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }
  MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
    if (!onboardDone || showHelp) {
      Onboarding(onFinish = {
        scope.launch { ctx.ds.edit { it[Keys.ONBOARD_DONE] = true } }
        showHelp = false
      })
      return@MaterialTheme
    }
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
          listOf("En vivo", "Movimientos", "Config").forEachIndexed { i, t ->
            Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
          }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
          when (tab) {
            0 -> Live(btnColor)
            1 -> Movs(btnColor)
            2 -> Config(dark, { dark = it }, keepOn, { keepOn = it }, btnColor, onHelp = { showHelp = true })
          }
        }
      }
    }
  }
}

@Composable
fun ServiceLedButton(serviceOn: Boolean, onToggle: () -> Unit) {
  val blink by rememberInfiniteTransition(label = "led").animateFloat(
    initialValue = 1f, targetValue = 0.25f,
    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink"
  )
  val led = if (serviceOn) Color(0xFF00C853) else Color(0xFFD32F2F)
  Button(
    onClick = onToggle,
    colors = ButtonDefaults.buttonColors(
      containerColor = if (serviceOn) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
      contentColor = Color.Black
    ),
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    shape = RoundedCornerShape(20.dp)
  ) {
    Canvas(Modifier.size(12.dp).alpha(if (serviceOn) blink else 1f)) { drawCircle(led) }
    Spacer(Modifier.width(6.dp))
    Text(if (serviceOn) "ON" else "OFF", style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
fun Live(btnColor: Int) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var list by remember { mutableStateOf<List<Movement>>(emptyList()) }
  var turbo by remember { mutableStateOf(PollService.instance?.turboOn == true) }
  var baseSec by remember { mutableStateOf(60) }
  var serviceOn by remember { mutableStateOf(true) }
  var showSuccess by remember { mutableStateOf(false) }
  val announcer = remember { Announcer(ctx) }
  DisposableEffect(Unit) { onDispose { announcer.release() } }

  suspend fun reload() {
    val db = Room.databaseBuilder(ctx, AppDb::class.java, "notifmp.db").build()
    try {
      list = db.movements().last(10)
      val prefs = ctx.ds.data.first()
      baseSec = prefs[Keys.NORMAL_SEC]?.coerceIn(10, 60) ?: 60
      serviceOn = prefs[Keys.SERVICE_ON] != false
      turbo = PollService.instance?.turboOn == true
    } catch (_: Exception) {} finally { try { db.close() } catch (_: Exception) {} }
  }

  fun onNewPayment(amount: Double, payer: String?) {
    announcer.soundOn = true
    announcer.playMp()
    try {
      val vib = if (Build.VERSION.SDK_INT >= 31) {
        val vm = ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION") ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
      }
      vib?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
    } catch (_: Exception) {}
    showSuccess = true
  }

  LaunchedEffect(Unit) {
    reload()
    while (true) {
      kotlinx.coroutines.delay(2000)
      val before = list.firstOrNull()?.mpId
      reload()
      val after = list.firstOrNull()
      if (before != null && after != null && after.mpId != before) {
        if (turbo) turbo = false
        if (serviceOn) onNewPayment(after.amount, after.payer)
      }
      if (turbo && before != null && after?.mpId != before) turbo = false
      if (!turbo && PollService.instance?.turboOn == false) turbo = false
    }
  }
  val top = list.firstOrNull()
  Box(Modifier.fillMaxSize()) {
    Column(
      Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Última transferencia", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ServiceLedButton(serviceOn = serviceOn) {
          scope.launch {
            if (serviceOn) {
              ctx.ds.edit { it[Keys.SERVICE_ON] = false }
              PollService.stop(ctx)
              serviceOn = false
              turbo = false
              Toast.makeText(ctx, "Monitoreo pausado", Toast.LENGTH_SHORT).show()
            } else {
              ctx.ds.edit { it[Keys.SERVICE_ON] = true }
              PollService.start(ctx)
              serviceOn = true
              Toast.makeText(ctx, "Monitoreo activo", Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
        if (top != null) fmtARS(top.amount) else "$ —",
        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
        textAlign = TextAlign.Center, maxLines = 1
      )
      Text(
        if (top != null) fmtFechaHora(top.notifiedAt) else "Sin transferencias aún",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        if (!serviceOn) "Pausado"
        else "Cada ${if (turbo) 2 else baseSec}s ${if (turbo) "· TURBO" else ""}",
        style = MaterialTheme.typography.labelMedium
      )
      Spacer(Modifier.height(12.dp))
      Text("Últimas 10", style = MaterialTheme.typography.titleMedium)
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        items(list.take(10)) { m ->
          Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtARS(m.amount), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
            Text(fmtFechaHora(m.notifiedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        if (list.isEmpty()) item { Text("Aún no hay movimientos", style = MaterialTheme.typography.bodySmall) }
      }
      Spacer(Modifier.height(8.dp))
      Button(
        enabled = !turbo && serviceOn,
        onClick = { scope.launch { PollService.turbo(ctx, 10); turbo = true } },
        colors = appBtnColors(btnColor),
        modifier = Modifier.wrapContentWidth()
      ) { Text(if (turbo) "Turbo 2s activo…" else "Turbo 2s") }
      Spacer(Modifier.height(4.dp))
    }
    if (showSuccess) {
      Dialog(onDismissRequest = { showSuccess = false }) {
        val comp by rememberLottieComposition(LottieCompositionSpec.Asset("success.json"))
        val progress by animateLottieCompositionAsState(comp, iterations = 1)
        LaunchedEffect(progress) { if (progress == 1f) { kotlinx.coroutines.delay(400); showSuccess = false } }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
          val size = if (maxWidth < 360.dp) 180.dp else 240.dp
          Box(Modifier.fillMaxWidth().wrapContentSize(Alignment.Center)) {
            LottieAnimation(comp, progress = { progress }, modifier = Modifier.size(size))
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Movs(btnColor: Int) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var list by remember { mutableStateOf<List<Movement>>(emptyList()) }
  var sel by remember { mutableStateOf<Movement?>(null) }
  var fromD by remember { mutableStateOf("") }
  var toD by remember { mutableStateOf("") }
  var showFrom by remember { mutableStateOf(false) }
  var showTo by remember { mutableStateOf(false) }
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
    try { list = db.movements().last(200) } catch (_: Exception) {} finally { try { db.close() } catch (_: Exception) {} }
  }
  val df = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("America/Argentina") } }
  val today = df.format(Date())
  fun dayOf(s: String) = list.filter { df.format(Date(it.notifiedAt)) == s }.sumOf { it.amount }
  fun pickDate(current: String): Long? {
    return try { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(current)?.time } catch (_: Exception) { null }
  }
  Scaffold(bottomBar = {
    Row(
      Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
      Button(onClick = { csvLauncher.launch("notif-mp.csv") }, colors = appBtnColors(btnColor)) { Text("CSV") }
      Button(onClick = { xlsxLauncher.launch("notif-mp.xlsx") }, colors = appBtnColors(btnColor)) { Text("Excel") }
    }
  }) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
      Text("Hoy: ${fmtARSshort(dayOf(today))}", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1)
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(fromD, {}, readOnly = true, label = { Text("Desde") }, trailingIcon = {
          Icon(Icons.Filled.CalendarMonth, null, Modifier.clickable { showFrom = true })
        }, modifier = Modifier.weight(1f).clickable { showFrom = true })
        OutlinedTextField(toD, {}, readOnly = true, label = { Text("Hasta") }, trailingIcon = {
          Icon(Icons.Filled.CalendarMonth, null, Modifier.clickable { showTo = true })
        }, modifier = Modifier.weight(1f).clickable { showTo = true })
      }
      Button(onClick = {
        scope.launch {
          val db = Room.databaseBuilder(ctx, AppDb::class.java, "notifmp.db").build()
          list = try {
            if (fromD.isBlank() && toD.isBlank()) db.movements().last(200)
            else {
              val f = if (fromD.isBlank()) 0 else SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(fromD)?.time ?: 0
              val t = if (toD.isBlank()) System.currentTimeMillis() + 86400000
              else (SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(toD)?.time ?: System.currentTimeMillis()) + 86400000
              db.movements().range(f, t)
            }
          } catch (_: Exception) { db.movements().last(200) }
          finally { try { db.close() } catch (_: Exception) {} }
        }
      }, colors = appBtnColors(btnColor), modifier = Modifier.padding(vertical = 8.dp)) { Text("Filtrar") }
      LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(list) { m ->
          TextButton(onClick = { sel = m }, modifier = Modifier.fillMaxWidth()) {
            Text("${fmtHoraCorta(m.notifiedAt)} · ${fmtARS(m.amount)}", maxLines = 1)
          }
        }
        if (list.isEmpty()) item { Text("Sin movimientos para el filtro", style = MaterialTheme.typography.bodySmall) }
      }
    }
  }
  if (showFrom) {
    val state = rememberDatePickerState(initialSelectedDateMillis = pickDate(fromD) ?: System.currentTimeMillis())
    DatePickerDialog(onDismissRequest = { showFrom = false }, confirmButton = {
      TextButton(onClick = {
        state.selectedDateMillis?.let { fromD = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) }
        showFrom = false
      }) { Text("OK") }
    }, dismissButton = { TextButton(onClick = { showFrom = false }) { Text("Cancelar") } }) { DatePicker(state) }
  }
  if (showTo) {
    val state = rememberDatePickerState(initialSelectedDateMillis = pickDate(toD) ?: System.currentTimeMillis())
    DatePickerDialog(onDismissRequest = { showTo = false }, confirmButton = {
      TextButton(onClick = {
        state.selectedDateMillis?.let { toD = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it)) }
        showTo = false
      }) { Text("OK") }
    }, dismissButton = { TextButton(onClick = { showTo = false }) { Text("Cancelar") } }) { DatePicker(state) }
  }
  sel?.let { m ->
    AlertDialog(onDismissRequest = { sel = null }, confirmButton = {
      TextButton(onClick = { sel = null }) { Text("Cerrar") }
    }, title = { Text("${fmtARS(m.amount)} ARS") },
      text = { Text("Fecha: ${Date(m.notifiedAt)}\nPagador: ${m.payer ?: "-"}\nEmail: ${m.email ?: "-"}\nPayment ID: ${m.mpId}\nTipo: ${m.type ?: "-"}\nAprobada: ${m.dateApproved ?: "-"}") })
  }
}

@Composable
fun LogsDialog(onClose: () -> Unit) {
  val ctx = LocalContext.current
  AlertDialog(onDismissRequest = onClose, confirmButton = {
    TextButton(onClick = onClose) { Text("Cerrar") }
  }, title = { Text("Logs de depuración") }, text = {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
          val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
          clip.setPrimaryClip(android.content.ClipData.newPlainText("diag", OauthLog.dump()))
          Toast.makeText(ctx, "Copiado", Toast.LENGTH_SHORT).show()
        }) { Text("Copiar") }
        OutlinedButton(onClick = { OauthLog.clear() }) { Text("Limpiar") }
      }
      LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
        items(OauthLog.rows.toList()) { r ->
          Text("${r.time} [${r.stage}] ${r.detail}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
        }
        if (OauthLog.rows.isEmpty()) item { Text("Sin logs", style = MaterialTheme.typography.bodySmall) }
      }
    }
  })
}

@Composable
fun Config(dark: Boolean, onDark: (Boolean) -> Unit, keepOn: Boolean, onKeep: (Boolean) -> Unit, btnColor: Int, onHelp: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var linked by remember { mutableStateOf(TokenStore(ctx).linked()) }
  var sound by remember { mutableStateOf(true) }
  var ttsOn by remember { mutableStateOf(true) }
  var normal by remember { mutableStateOf("60") }
  var authUrl by remember { mutableStateOf<String?>(null) }
  var manualCode by remember { mutableStateOf("") }
  var selColor by remember(btnColor) { mutableStateOf(btnColor) }
  var showLogs by remember { mutableStateOf(false) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(Unit) {
    ctx.ds.data.first().let {
      sound = it[Keys.SOUND] ?: true
      ttsOn = it[Keys.TTS] ?: true
      normal = ((it[Keys.NORMAL_SEC] ?: 60).toString())
    }
  }
  DisposableEffect(lifecycle) {
    val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
      if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) linked = TokenStore(ctx).linked()
    }
    lifecycle.addObserver(obs)
    onDispose { lifecycle.removeObserver(obs) }
  }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    ElevatedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("1 · Vinculación", style = MaterialTheme.typography.titleMedium)
        Text("OAuth MP: " + if (linked) "Vinculado ✓" else "No vinculado")
        if (!linked) {
          Button(onClick = {
            val v = Pkce.verifier()
            val state = UUID.randomUUID().toString()
            ctx.getSharedPreferences("pkce", 0).edit().putString("v_$state", v).putString("last_state", state).apply()
            val url = authUrl(Pkce.challenge(v), state)
            authUrl = url
            openAuth(ctx, url)
          }, colors = appBtnColors(selColor)) { Text("Vincular con Mercado Pago") }
          authUrl?.let { u ->
            Text("QR de vinculación (escaneable desde otro equipo):")
            Image(qrBitmap(u, 420).asImageBitmap(), contentDescription = "QR vincular", modifier = Modifier.size(210.dp))
          }
          OutlinedTextField(manualCode, { manualCode = it }, label = { Text("Pegar code manual (si no volvió sola)") }, modifier = Modifier.fillMaxWidth())
          Button(onClick = {
            scope.launch {
              try {
                val prefs = ctx.getSharedPreferences("pkce", 0)
                val state = prefs.getString("last_state", null)
                val verifier = prefs.getString("v_$state", null)
                if (state.isNullOrEmpty() || verifier.isNullOrEmpty() || manualCode.isBlank()) {
                  Toast.makeText(ctx, "Falta vincular primero o pegar el code", Toast.LENGTH_LONG).show()
                  return@launch
                }
                val r = proxy().exchange(mapOf("code" to manualCode.trim(), "code_verifier" to verifier, "redirect_uri" to BuildConfig.REDIRECT_URI))
                if (r.access_token != null) {
                  TokenStore(ctx).save(r.access_token, r.refresh_token, r.expires_in ?: 15552000, r.user_id?.toString())
                  linked = true
                } else Toast.makeText(ctx, "El proxy no devolvió token", Toast.LENGTH_LONG).show()
              } catch (e: Exception) {
                OauthLog.add("manual", "FAIL ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(ctx, "Error: ${e.message}", Toast.LENGTH_LONG).show()
              }
            }
          }, colors = appBtnColors(selColor)) { Text("Canjear código") }
        } else {
          Button(onClick = { scope.launch { TokenStore(ctx).clear(); linked = false } }, colors = appBtnColors(selColor)) { Text("Desvincular") }
        }
      }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("2 · Apariencia", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Modo oscuro"); Spacer(Modifier.width(8.dp)); Switch(dark, onDark) }
        Text("Color de botones")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          BTN_PRESET.forEach { c ->
            Box(
              Modifier.size(36.dp).clip(CircleShape).background(Color(c)).clickable { selColor = c }
                .then(if (selColor == c) Modifier.padding(2.dp) else Modifier)
            )
          }
        }
      }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("3 · Avisos y servicio", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Sonido"); Spacer(Modifier.width(8.dp)); Switch(sound, { sound = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Monto hablado (TTS)"); Spacer(Modifier.width(8.dp)); Switch(ttsOn, { ttsOn = it }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("No apagar pantalla"); Spacer(Modifier.width(8.dp)); Switch(keepOn, onKeep) }
        OutlinedTextField(normal, { v -> normal = v.filter { it.isDigit() }.take(2) }, label = { Text("Intervalo base (10-60s)") }, modifier = Modifier.fillMaxWidth())
      }
    }
    Button(onClick = {
      scope.launch {
        ctx.ds.edit { it[Keys.NORMAL_SEC] = (normal.toIntOrNull() ?: 60).coerceIn(10, 60); it[Keys.SOUND] = sound; it[Keys.TTS] = ttsOn; it[Keys.SERVICE_ON] = true; it[Keys.BTN_COLOR] = selColor }
        PollService.start(ctx)
        Toast.makeText(ctx, "Configuración guardada ✓", Toast.LENGTH_SHORT).show()
      }
    }, colors = appBtnColors(selColor), modifier = Modifier.fillMaxWidth()) { Text("Guardar e iniciar") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { showLogs = true }, modifier = Modifier.weight(1f)) { Text("Ver logs") }
      OutlinedButton(onClick = onHelp, modifier = Modifier.weight(1f)) { Text("Ver ayuda") }
    }
  }
  if (showLogs) LogsDialog { showLogs = false }
}

data class OnbPage(val icon: ImageVector, val title: String, val body: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onFinish: () -> Unit) {
  val pages = listOf(
    OnbPage(Icons.Filled.Link, "Vinculá Mercado Pago", "En Config tocá Vincular, autorizá en Mercado Pago y volvé. Podés usar el QR desde otro equipo o pegar el código manual."),
    OnbPage(Icons.Filled.Bolt, "Monitoreo y LED", "El punto verde parpadea mientras consulta. Si lo tocás pasa a rojo y pausa todos los requests y avisos. El Turbo consulta cada 2s."),
    OnbPage(Icons.Filled.CheckCircle, "Aviso de cobro", "Al recibir $ verás la animación en el centro, suena el tono MP y se anuncia el monto. Queda primera en Últimas 10."),
    OnbPage(Icons.Filled.TableChart, "Movimientos y Excel", "Hoy: $ total del día. Filtrá por fecha con el calendario y exportá con los botones fijos CSV / Excel."),
  )
  val pager = rememberPagerState(pageCount = { pages.size })
  val scope = rememberCoroutineScope()
  Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
      Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(pages[i].icon, null, modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(pages[i].title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(pages[i].body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
      }
    }
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.Center) {
      repeat(pages.size) { dot ->
        Box(Modifier.padding(4.dp).size(if (pager.currentPage == dot) 12.dp else 8.dp).clip(CircleShape)
          .background(if (pager.currentPage == dot) MaterialTheme.colorScheme.primary else Color.Gray))
      }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onFinish) { Text("Saltar") }
      Button(onClick = {
        if (pager.currentPage == pages.lastIndex) onFinish()
        else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
      }) { Text(if (pager.currentPage == pages.lastIndex) "Empezar" else "Siguiente") }
    }
  }
}
