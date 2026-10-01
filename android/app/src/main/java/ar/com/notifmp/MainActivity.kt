package ar.com.notifmp

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.Room
import com.google.android.gms.ads.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : ComponentActivity() {
  private var tts: TextToSpeech? = null
  override fun onCreate(s: Bundle?) {
    super.onCreate(s)
    MobileAds.initialize(this)
    tts = TextToSpeech(this, null)
    setContent { App() }
  }
  override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

@Composable
fun App() {
  var tab by remember { mutableIntStateOf(0) }
  Scaffold(
    bottomBar = {
      Column {
        AndroidView(factory = { c ->
          AdView(c).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(c, 360))
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            loadAd(AdRequest.Builder().build())
          }
        }, modifier = Modifier.fillMaxWidth().height(60.dp))
      }
    }
  ) { pad ->
    Column(Modifier.padding(pad)) {
      TabRow(selectedTabIndex = tab) {
        listOf("En vivo", "Movimientos", "Configuración").forEachIndexed { i, t ->
          Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
        }
      }
      when (tab) {
        0 -> LiveScreen()
        1 -> MovsScreen()
        2 -> ConfigScreen()
      }
    }
  }
}

@Composable
fun LiveScreen() {
  var last by remember { mutableStateOf<List<Movement>>(emptyList()) }
  LaunchedEffect(Unit) { }
  Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    val top = last.firstOrNull()
    Text(if (top != null) "$ ${top.amount} ARS" else "—", style = MaterialTheme.typography.displayLarge)
    Spacer(Modifier.height(8.dp))
    Text("Últimas 5 transferencias", style = MaterialTheme.typography.titleMedium)
    LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
      items(last.take(5)) { m -> Text("$ ${m.amount} · ${m.payer ?: m.mpId}") }
    }
  }
}

@Composable
fun MovsScreen() {
  var list by remember { mutableStateOf<List<Movement>>(emptyList()) }
  var sel by remember { mutableStateOf<Movement?>(null) }
  Column(Modifier.fillMaxSize().padding(16.dp)) {
    Row { Text("Total hoy: $ X  ·  Total ayer: $ Y") }
    Spacer(Modifier.height(8.dp))
    Row { Button(onClick = {}) { Text("Exportar") } }
    LazyColumn {
      items(list) { m ->
        TextButton(onClick = { sel = m }) { Text("${m.mpId} · $ ${m.amount}") }
      }
    }
  }
  sel?.let { m ->
    AlertDialog(onDismissRequest = { sel = null }, confirmButton = {
      TextButton(onClick = { sel = null }) { Text("Cerrar") }
    }, title = { Text("$ ${m.amount} ARS") },
      text = { Text("ID: ${m.mpId}\nPagador: ${m.payer ?: "-"}\nEmail: ${m.email ?: "-"}\nFecha: ${m.dateApproved ?: "-"}\nTipo: ${m.type ?: "-"}") })
  }
}

@Composable
fun ConfigScreen() {
  val ctx = androidx.compose.ui.platform.LocalContext.current
  val scope = rememberCoroutineScope()
  var linked by remember { mutableStateOf(TokenStore(ctx).linked()) }
  var dark by remember { mutableStateOf(false) }
  var sound by remember { mutableStateOf(true) }
  var ttsOn by remember { mutableStateOf(true) }
  var verifier by remember { mutableStateOf("") }
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Estado OAuth MP: " + if (linked) "Vinculado ✓" else "No vinculado")
    if (!linked) {
      Button(onClick = {
        verifier = Pkce.verifier()
        val state = UUID.randomUUID().toString()
        ctx.getSharedPreferences("pkce", 0).edit().putString("v_$state", verifier).putString("last_state", state).apply()
        openAuth(ctx, authUrl(Pkce.challenge(verifier), state))
      }) { Text("Vincular con Mercado Pago") }
      Text("O escaneá el QR con la URL de vinculación (se genera con el mismo link).")
    } else {
      Button(onClick = { TokenStore(ctx).clear(); linked = false }) { Text("Desvincular") }
    }
    Row { Text("Modo oscuro"); Spacer(Modifier.width(8.dp)); Switch(dark, { dark = it }) }
    Row { Text("Sonido"); Spacer(Modifier.width(8.dp)); Switch(sound, { sound = it }) }
    Row { Text("Anunciar monto por voz"); Spacer(Modifier.width(8.dp)); Switch(ttsOn, { ttsOn = it }) }
    Button(onClick = { PollService.start(ctx) }) { Text("Iniciar monitoreo") }
  }
}
