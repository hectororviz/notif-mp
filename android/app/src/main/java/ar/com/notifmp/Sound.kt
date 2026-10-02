package ar.com.notifmp

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.util.Locale

class Announcer(ctx: Context) {
  private val appCtx = ctx.applicationContext
  private var ttsReady = false
  private var pending: String? = null
  private var tts: TextToSpeech? = TextToSpeech(appCtx) { st ->
    ttsReady = st == TextToSpeech.SUCCESS
    pending?.let { txt ->
      pending = null
      if (ttsReady) speakNow(txt)
    }
  }
  private var player: MediaPlayer? = null
  var soundOn = true; var ttsOn = true

  fun playMp() {
    if (!soundOn) return
    try {
      player?.release()
      player = null
      val resId = appCtx.resources.getIdentifier("mp", "raw", appCtx.packageName)
      if (resId == 0) return
      val afd = appCtx.resources.openRawResourceFd(resId) ?: return
      player = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()
        prepare()
      }
      player?.setOnCompletionListener { it.release(); player = null }
      player?.start()
    } catch (_: Exception) {}
  }

  private fun speakNow(txt: String) {
    try {
      tts?.language = Locale("es", "AR")
      tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "notifmp")
    } catch (_: Exception) {}
  }

  fun announce(amount: Double, payer: String?) {
    playMp()
    if (!ttsOn) return
    val txt = "Transferencia recibida, ${"%.0f".format(amount)} pesos" + (payer?.let { ", de $it" } ?: "")
    if (ttsReady) speakNow(txt) else pending = txt
  }
  fun release() { try { tts?.shutdown() } catch (_: Exception) {}; try { player?.release() } catch (_: Exception) {}; player = null }
}
