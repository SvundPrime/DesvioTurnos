package com.example.desviosturnos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class CallAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_ALARM_TYPE) ?: ""
        val applyNow = intent.getBooleanExtra(EXTRA_ALARM_APPLY_NOW, false)

        Log.d("CALL_ALARM", "onReceive type=$type applyNow=$applyNow extras=${intent.extras?.keySet()}")

        if (type != ALARM_TYPE_AUTO_BOUNDARY && !applyNow) {
            Log.w("CALL_ALARM", "Ignoring alarm: not AUTO_BOUNDARY")
            return
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "desvios:alarm")
        wl.acquire(5_000)

        try {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_ALARM_APPLY_NOW, true)
                putExtra(EXTRA_ALARM_TYPE, ALARM_TYPE_AUTO_BOUNDARY)
            }
            context.startActivity(i)
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
