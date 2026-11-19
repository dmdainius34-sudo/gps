package lt.example.gnss

import android.content.Context

/**
 * Rankinis palydovo užtildymas (C/N0 grafike – jungikliai pagal sistemą, palydovo kortelėje – mygtukas): užtildytas palydovas
 * neįtraukiamas į programos padėtį, kol vėl įjungiamas. Raktas – kaip palydovų sąraše („GPS12“, „BDS37“). Išsaugoma.
 */
object ManualMute {
    @Volatile var keys: Set<String> = emptySet()
        private set
    private var ctx: Context? = null

    fun load(c: Context) {
        ctx = c.applicationContext
        keys = c.getSharedPreferences("manual_mute", Context.MODE_PRIVATE).getStringSet("keys", emptySet())?.toSet() ?: emptySet()
    }

    fun has(key: String) = key in keys

    @Synchronized fun set(key: String, muted: Boolean) {
        keys = if (muted) keys + key else keys - key
        ctx?.getSharedPreferences("manual_mute", Context.MODE_PRIVATE)?.edit()?.putStringSet("keys", keys)?.apply()
    }
}
