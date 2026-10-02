package ar.com.notifmp

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale

class Announcer(ctx: Context) {
  private val appCtx = ctx.applicationContext
  private var tts: TextToSpeech? = TextToSpeech(appCtx, null)
  private var player: MediaPlayer? = null
  var soundOn = true; var ttsOn = true

  fun playMp() {
    if (!soundOn) return
    try {
      player?.release()
      val resId = appCtx.resources.getIdentifier("mp", "raw", appCtx.packageName)
      player = if (resId != 0) MediaPlayer.create(appCtx, resId) else null
      player?.setOnCompletionListener { it.release(); player = null }
      player?.start()
    } catch (_: Exception) {}
  }

  fun announce(amount: Double, payer: String?) {
    playMp()
    if (ttsOn) {
      val txt = "Transferencia recibida, ${"%.0f".format(amount)} pesos" + (payer?.let { ", de $it" } ?: "")
      try {
        tts?.language = Locale("es", "AR")
        tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "notifmp")
      } catch (_: Exception) {}
    }
  }
  fun release() { try { tts?.shutdown() } catch (_: Exception) {}; try { player?.release() } catch (_: Exception) {} }
}
