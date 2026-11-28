package lt.example.gnss

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Programos garsai (sintezuojami SfxSynth): grojama atskiroje gijoje, medijos garsumu (USAGE_GAME).
 * „blowgun“ – spjūvis per bambuko vamzdį (skrydyje palikus Šiaurinę žvaigždę).
 */
object Sfx {
    private const val SR = 44100
    private val cache = HashMap<String, ShortArray>()

    fun play(name: String) {
        Thread {
            try {
                val pcm = synchronized(cache) {
                    cache[name] ?: when (name) { "blowgun" -> SfxSynth.blowgun(SR); else -> null }?.also { cache[name] = it }
                } ?: return@Thread
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                t.write(pcm, 0, pcm.size)
                t.play()
                Thread.sleep(pcm.size * 1000L / SR + 300)
                t.release()
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}
