<p align="center">
  <img src="assets/icon.svg" width="96" height="96" alt="Ícono de LyricWidget">
</p>

<h1 align="center">LyricWidget</h1>

<p align="center">
  Un widget para la pantalla de inicio que muestra, línea por línea y sincronizada, la letra de la canción que está sonando en <b>cualquier reproductor</b>.<br>
  Sin backend y sin cuentas: una app monolítica que consulta APIs públicas y gratuitas.
</p>

<p align="center">
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-sin%20dependencias-7F52FF?logo=kotlin&logoColor=white">
  <img alt="APK ~71 KB" src="https://img.shields.io/badge/APK-~71%20KB-6D28D9">
  <img alt="Sin backend" src="https://img.shields.io/badge/backend-ninguno-0F172A">
</p>

---

## ¿Qué hace?

```
╭──────────────────────────────────────────────────────────╮
│  Canción · Artista                          −  +0.5 s  + │
│                                                          │
│  línea anterior                                          │
│  LÍNEA ACTUAL, EN GRANDE Y CON COLOR                     │
│  línea siguiente                                         │
│  la que viene después                                    │
╰──────────────────────────────────────────────────────────╯
                     Letra en vivo (4×2, redimensionable)
```

1. **Detecta lo que suena** en Spotify, YouTube Music, Deezer, Apple Music, SoundCloud, reproductores locales… cualquier app que publique una sesión multimedia de Android.
2. **Busca la letra** en [LRCLIB](https://lrclib.net) (letras sincronizadas en formato LRC) y, si no está, en [lyrics.ovh](https://lyrics.ovh) (solo texto).
3. **La muestra en el widget**, avanzando sola al ritmo de la canción, incluso al adelantar, retroceder o pausar.

## Características

| Función | Detalle |
|---|---|
| **Widget redimensionable** | Muestra de 1 a 4 líneas según el alto que le des; calcula qué cabe para que ninguna quede cortada. |
| **Ajuste de desfase** | Botones `−` / `+` en el propio widget (pasos de 0.5 s). Se guarda por canción. |
| **Búsqueda manual** | Si no encuentra la letra o trae la equivocada, corrige artista/título en la app y elige el resultado correcto. |
| **Caché offline** | Cada letra se descarga una sola vez y queda guardada; funciona sin internet para canciones ya escuchadas. |
| **Limpieza de títulos** | Quita "Remastered 2011", "(Official Video)", "feat. …", "- Topic", "VEVO"… y entiende títulos de YouTube tipo "Artista - Canción". |
| **Apariencia** | Fondo tomado del color de la **portada del álbum**, oscuro, negro OLED, cristal o claro; 18 colores para la línea actual; 4 tamaños de letra. |
| **Letras sin tiempos** | Si solo hay texto plano, reparte las líneas a lo largo de la canción (marcado como "aprox."). |
| **Tolerante a fallos** | Si LRCLIB está saturado (responde 503), reintenta con consultas más simples y, si sigue caído, vuelve a intentar solo cada 30 s. |

## Cero impacto en batería

- El widget **no se actualiza por temporizador** (`updatePeriodMillis = 0`).
- En lugar de consultar la posición cada X ms, la app calcula cuánto falta para la siguiente línea y **duerme exactamente ese tiempo**.
- Con la música en pausa, la pantalla apagada o sin widgets en el escritorio, **no corre ningún reloj**.
- No hay red salvo al cambiar de canción (y solo si la letra no está en caché).

## ¿Cómo sabe qué está sonando?

Igual que [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler): Android solo deja leer las sesiones multimedia de otras apps (`MediaSessionManager.getActiveSessions`) a quien tiene **acceso a notificaciones**. La app declara un `NotificationListenerService` únicamente para obtener ese permiso; **las notificaciones se ignoran por completo**.

---

## Instalación

### Opción A: compilar e instalar por USB

**Requisitos:** JDK 17+ y Android SDK con `platform-tools`.

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

> **En Windows:** usa el `adb.exe` del SDK (`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`) si Android Studio está abierto, para que no choquen dos servidores ADB.

### Primeros pasos

1. Abre **LyricWidget** → **Conceder acceso** → activa *LyricWidget* en la lista.
   O por ADB:
   ```bash
   adb shell cmd notification allow_listener com.lyricwidget.app/com.lyricwidget.app.NowPlayingService
   ```
2. Mantén presionado el escritorio → **Widgets** → **Letra en vivo**.
3. Pon música. Tocar el título del widget abre el reproductor; tocar la letra abre los ajustes.

---

## Estructura del repositorio

```
app/src/main/java/com/lyricwidget/app/
├── NowPlayingService.kt   Escucha las sesiones multimedia y elige la que suena
├── NowPlaying.kt          Estado compartido, desfase y reloj de líneas
├── Track.kt               Metadatos de la canción y limpieza de títulos
├── Lyrics.kt              Parser LRC y cálculo de la línea actual
├── LyricsRepository.kt    LRCLIB + lyrics.ovh + caché en disco (HTTP sin librerías)
├── LyricsWidget.kt        El widget (RemoteViews) y su ajuste al tamaño
├── WidgetTheme.kt         Fondos, colores y tamaños de letra
└── MainActivity.kt        Acceso, sonando ahora, búsqueda manual, apariencia y caché
```

## Créditos

- Letras: [LRCLIB](https://lrclib.net) y [lyrics.ovh](https://lyrics.ovh), APIs públicas y gratuitas. La app no incluye ninguna letra: se descargan en el dispositivo bajo demanda.
- Detección de reproducción inspirada en [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler).
- Estructura y estilo visual heredados de [PadControl](https://github.com/Bloodiewormer/padcontrol-app).
