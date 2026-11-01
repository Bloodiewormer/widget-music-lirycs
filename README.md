<p align="center">
  <img src="assets/icon.svg" width="96" height="96" alt="Ícono de LyricWidget">
</p>

<h1 align="center">LyricWidget</h1>

<p align="center">
  Un widget para la pantalla de inicio que muestra, línea por línea y sincronizada, la letra de la canción que está sonando en <b>cualquier reproductor</b>.<br>
  Sin backend y sin cuentas: una app monolítica que consulta APIs públicas y gratuitas.
</p>

<p align="center">
  <a href="https://github.com/Bloodiewormer/widget-music-lirycs/releases/latest">
    <img alt="Descargar APK" src="https://img.shields.io/badge/Descargar-APK%20v1.3-FF5A1F?logo=android&logoColor=white">
  </a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-sin%20dependencias-7F52FF?logo=kotlin&logoColor=white">
  <img alt="APK ~86 KB" src="https://img.shields.io/badge/APK-~86%20KB-FF5A1F">
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
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━──────────────────────── │
╰──────────────────────────────────────────────────────────╯
                     Letra en vivo (4×2, redimensionable)
```

1. **Detecta lo que suena** en Spotify, YouTube Music, Deezer, Apple Music, SoundCloud, reproductores locales… cualquier app que publique una sesión multimedia de Android.
2. **Busca la letra** en [LRCLIB](https://lrclib.net) (letras sincronizadas en formato LRC) y, si no está, en [lyrics.ovh](https://lyrics.ovh) (solo texto).
3. **La muestra en el widget**, avanzando sola al ritmo de la canción, incluso al adelantar, retroceder o pausar.

## Características

| Función | Detalle |
|---|---|
| **Widget redimensionable** | En equipos modernos da prioridad a la línea actual: si es larga, ocupa varias filas y ceden las líneas vecinas. En Android 8 conserva la distribución ligera de la v1.2. Barra fina con el avance de la canción. |
| **Reloj cuando no hay música** | Sin reproductor activo, o tras 2 minutos en pausa, el widget muestra la hora y la fecha (`TextClock`, que Android actualiza sin despertar a la app). |
| **Ajuste de desfase** | Botones `−` / `+` en el propio widget (pasos de 0.5 s), guardados por canción. La app aprende de tus correcciones: tras ajustar un par de canciones con el mismo reproductor y salida de audio (por ejemplo, audífonos Bluetooth), las canciones nuevas empiezan ya corregidas y se marcan como "auto". |
| **Animación suave** | En el widget la línea nueva entra mientras la anterior se desvanece; en la letra completa la línea actual se ilumina y se centra. Si una parte de la canción es más corta que la animación, el cambio es instantáneo en lugar de quedar cortado. Respeta la opción del sistema que desactiva animaciones. |
| **Refresco ligero** | El avance de la barra actualiza solo esa vista; no reinicia la animación ni vuelve a medir las letras en cada paso. |
| **Letra completa** | Toca la letra en el widget para verla entera, sobre el color de la portada, con la línea actual resaltada y centrada. Toca cualquier línea para saltar a ese momento de la canción. Incluye desfase, tamaño de letra `A−`/`A+` y mantiene la pantalla encendida mientras suena (modo karaoke). |
| **Búsqueda manual** | Si no encuentra la letra o trae la equivocada, corrige artista/título en la app y elige el resultado correcto. |
| **Caché offline** | Cada letra se descarga una sola vez y queda guardada; funciona sin internet para canciones ya escuchadas. |
| **Limpieza de títulos** | Quita "Remastered 2011", "(Official Video)", "feat. …", "- Topic", "VEVO"… y entiende títulos de YouTube tipo "Artista - Canción". |
| **Apariencia** | Fondo tomado del color de la **portada del álbum**, oscuro, negro OLED, cristal o claro; 18 colores para la línea actual; 4 tamaños de letra. Puedes elegir cuántas líneas vecinas mostrar y ocultar los controles − / +, el título o la barra de avance. |
| **Ignorar reproductores** | Desmarca las apps cuyas canciones no quieres ver (videos de YouTube, podcasts…). |
| **Tema oscuro** | La app sigue el modo oscuro del sistema (Android 10+). |
| **Letras sin tiempos** | Si solo hay texto plano, reparte las líneas a lo largo de la canción (marcado como "aprox."). |
| **Tolerante a fallos** | Si LRCLIB está saturado (responde 503), reintenta con consultas más simples y, si sigue caído, vuelve a intentar solo cada 30 s. |

## Se adapta a cada dispositivo

| | Android 8 o gama baja | Android 9 a 11 | Android 12 o superior |
|---|---|---|---|
| Cambio de línea en el widget | Deslizamiento con fundido de la v1.2 (380 ms) | Deslizamiento con fundido (340 ms) | Deslizamiento con fundido (340 ms) |
| Letra completa | Solo cambio de color, más rápido | Color y escala | Color y escala |
| Fondo de la letra completa | Color promedio de la portada | Color promedio de la portada | Portada difuminada (blur nativo) |
| Fondo del widget | Portada, oscuro, negro, cristal, claro | Igual | Además «Sistema» (Material You) |
| Barra de avance | Blanca o gris | Blanca o gris | Color de acento |
| Esquinas del widget | 24 dp | 24 dp | Las del sistema |
| Líneas visibles | Distribución ligera de la v1.2 | Ajustadas a la línea actual | Ajustadas a la línea actual con los tamaños exactos que informa el lanzador |

## Cero impacto en batería

- El widget **no se actualiza por temporizador** (`updatePeriodMillis = 0`).
- En lugar de consultar la posición cada X ms, la app calcula cuánto falta para la siguiente línea y **duerme exactamente ese tiempo**.
- La barra de avance se refresca como mucho cada 5 s, aprovechando los mismos despertares de las líneas.
- Con la música en pausa, la pantalla apagada o sin widgets en el escritorio, **no corre ningún reloj**.
- No hay red salvo al cambiar de canción (y solo si la letra no está en caché).

## ¿Cómo sabe qué está sonando?

Igual que [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler): Android solo deja leer las sesiones multimedia de otras apps (`MediaSessionManager.getActiveSessions`) a quien tiene **acceso a notificaciones**. La app declara un `NotificationListenerService` únicamente para obtener ese permiso; **las notificaciones se ignoran por completo**.

---

## Instalación

### Opción A: descargar el APK (recomendado)

**[Descargar LyricWidget-v1.3.apk](https://github.com/Bloodiewormer/widget-music-lirycs/releases/latest/download/LyricWidget-v1.3.apk)**

La v1.4 está en pruebas en el [PR #4](https://github.com/Bloodiewormer/widget-music-lirycs/pull/4); se publicará tras comprobar la transición restaurada en Android 8.

Ábrelo en el teléfono (permitiendo instalar apps desconocidas) o instálalo por USB:

```bash
adb install -r LyricWidget-v1.3.apk
```

### Opción B: compilar desde el código fuente

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
> **Android 13 o superior:** si al activar el acceso aparece «Configuración restringida», es porque la app se instaló desde un APK. Ve a *Ajustes → Apps → LyricWidget*, toca el menú ⋮ → **Permitir configuración restringida** y vuelve a intentarlo. La app muestra estos pasos y un botón directo cuando falta el acceso.

&
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
├── Players.kt             Reproductores vistos e ignorados
├── LyricsActivity.kt      Letra completa con la línea actual resaltada
└── MainActivity.kt        Acceso, sonando ahora, búsqueda manual, reproductores, apariencia y caché
```

## Créditos

- Letras: [LRCLIB](https://lrclib.net) y [lyrics.ovh](https://lyrics.ovh), APIs públicas y gratuitas. La app no incluye ninguna letra: se descargan en el dispositivo bajo demanda.
- Detección de reproducción inspirada en [Pano Scrobbler](https://github.com/kawaiiDango/pano-scrobbler).
- Estructura y estilo visual heredados de [PadControl](https://github.com/Bloodiewormer/padcontrol-app).
