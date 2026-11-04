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
    <img alt="Descargar APK" src="https://img.shields.io/badge/Descargar-APK%20v1.5.2-FF5A1F?logo=android&logoColor=white">
  </a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-sin%20dependencias-7F52FF?logo=kotlin&logoColor=white">
  <img alt="APK ~90 KB" src="https://img.shields.io/badge/APK-~90%20KB-FF5A1F">
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
│  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━────────────────────── │
╰──────────────────────────────────────────────────────────╯
                     Letra en vivo (4×2, redimensionable)
```

1. **Detecta lo que suena** en Spotify, YouTube Music, Deezer, Apple Music, SoundCloud, reproductores locales… cualquier app que publique una sesión multimedia de Android.
2. **Busca la letra** en [LRCLIB](https://lrclib.net) (letras sincronizadas en formato LRC) y, si no está, en [lyrics.ovh](https://lyrics.ovh) (solo texto).
3. **Pinta la estrofa en el widget** y salta de línea solo al compás de la música. Si corriges el desfase con los botones `+`/`−`, se guarda para esa canción y calibra la latencia global (p. ej. tus audífonos Bluetooth).
4. **Vuelve al reloj** si la música se detiene más de 2 minutos, o muestra la hora completa con fecha y batería si no hay nada sonando.

---

## Pantallas

<p align="center">
  <img src="assets/preview-widget.png" width="360" alt="Widget en el launcher con letra sincronizada">
  &nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/preview-app.png" width="360" alt="App de ajustes y vista completa de letra">
</p>

---

## Características

- **Sin servidores propios**: la app habla directo con LRCLIB y lyrics.ovh vía HTTPS usando solo `HttpURLConnection` del sistema.
- **Sin bibliotecas de terceros**: cero dependencias externas en `build.gradle.kts`. Ni Retrofit, ni Coroutines, ni Jetpack Glance, ni Gson.
- **Sincronización suave sin consumir batería**: en vez de consultar la posición cada pocos milisegundos, el widget duerme exactamente hasta el instante en que toca la próxima línea (`Handler.postDelayed`). Si la pantalla se apaga, no hay reloj corriendo.
- **Compensación de desfase inteligente**:
  - Ajuste manual por canción en pasos de 0.5 s desde el propio widget o desde la app.
  - Aprendizaje automático: tras ajustar un par de canciones con Bluetooth o altavoz local, la app calcula la mediana y la aplica a las canciones nuevas.
  - Tocar cualquier línea en la app salta el reproductor a ese momento exacto.
- **Búsqueda manual de respaldo**: si el reproductor reporta un título recortado o con sufijos raros (`(Remastered 2011)`, `feat. ...`), puedes buscar a mano en la app y vincular la letra correcta.
- **Caché local en disco**: las letras se guardan en el almacenamiento interno de la app en archivos LRC planos. No vuelve a gastar datos al repetir canciones.
- **Personalización**:
  - Paleta de color del widget: blanco puro, claro, ámbar, o tono automático extraído de la portada del álbum.
  - Modo reloj configurable cuando no hay música activa.
  - Posibilidad de ignorar apps específicas (p. ej. YouTube para videos o reproductores de podcasts).
- **Compatible con Android antiguo y moderno**: funciona desde Android 8.0 (Oreo, API 26) hasta Android 16 (Baklava). En Android 12+ aprovecha las esquinas redondeadas del sistema y el difuminado con la portada del álbum.

---

## Descarga e instalación

1. Ve a [Releases](https://github.com/Bloodiewormer/widget-music-lirycs/releases/latest) y descarga el archivo `app-release.apk` (pesa menos de 100 KB).
2. Instálalo en tu dispositivo Android (permite la instalación desde fuentes desconocidas si te lo pide el navegador).
3. Abre la app **LyricWidget** y pulsa el botón para conceder **Acceso a notificaciones** (es el permiso oficial que Android requiere para que una app pueda leer el estado y los metadatos de los reproductores multimedia vía `MediaSessionManager`).
4. Ve a tu pantalla de inicio, mantén presionado un espacio vacío, busca el widget **LyricWidget (4×2)** y colócalo donde prefieras.
5. Dale *Play* a cualquier canción en tu reproductor favorito.

---

## Compilar desde el código fuente

Requiere JDK 17 y Android SDK con `compileSdk = 37`.

```bash
git clone https://github.com/Bloodiewormer/widget-music-lirycs.git
cd widget-music-lirycs

# Compilar APK Release (firmada automáticamente con la clave de pruebas)
./gradlew assembleRelease

# El archivo generado queda en:
# app/build/outputs/apk/release/app-release.apk
```

Para instalarlo directamente por USB con `adb`:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Licencia

Distribuido bajo la licencia MIT. Consulta [LICENSE](LICENSE) para más detalles.
