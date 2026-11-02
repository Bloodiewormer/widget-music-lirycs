package com.lyricwidget.app

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Qué puede hacer este dispositivo. La app aprovecha lo moderno (Android 12+) y se limita en
 * equipos viejos o de gama baja, en vez de tratar a todos igual.
 */
object Device {
    /** Android 12+: blur nativo, colores Material You y teñido de widgets en tiempo de ejecución. */
    val modern: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Android 8 o equipos con poca RAM: distribución ligera y sin escalar texto en karaoke. */
    fun lite(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            context.getSystemService(ActivityManager::class.java).isLowRamDevice

    /** Respeta "Escala de animación: desactivada" de las opciones del sistema o de accesibilidad. */
    val animationsEnabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    /** Duración de la animación de cambio de línea en la letra completa. */
    fun lineAnimationMs(context: Context): Long = if (lite(context)) 220L else 420L

    /** Duración de la animación del widget (coincide con res/anim y res/anim-v28). */
    val widgetAnimationMs: Long get() = 220L
}
