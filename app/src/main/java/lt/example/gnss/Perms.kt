package lt.example.gnss

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Visi programos vykdymo leidimai vienoje vietoje – naujas leidimas pridedamas čia ir prašomas kartu su kitais. */
object Perms {
    fun runtime(): List<String> = buildList {
        add("android.permission.ACCESS_FINE_LOCATION")
        add("android.permission.ACCESS_COARSE_LOCATION")
        add("android.permission.CAMERA")
        if (Build.VERSION.SDK_INT >= 29) add("android.permission.ACTIVITY_RECOGNITION")
        if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
    }

    fun missing(ctx: Context): List<String> = runtime().filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
