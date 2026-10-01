package ar.com.notifmp

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale

class Announcer(ctx: Context) {
  private var tts: TextToSpeech? = TextToSpeech(ctx, null)
  private var player: MediaPlayer? = null
  var soundOn = true; var ttsOn = true
  fun announce(amount: Double, payer: String?) {
    if (soundOn) { try { player?.release(); player = null } catch (_: Exception) {} }
    if (ttsOn) {
      val txt = "Transferencia recibida, ${"%.0f".format(amount)} pesos" + (payer?.let { ", de $it" } ?: "")
      tts?.language = Locale("es", "AR")
      tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "notifmp")
    }
  }
  fun release() { tts?.shutdown(); player?.release() }
}
