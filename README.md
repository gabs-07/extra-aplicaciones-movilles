# 📺 BlueTube — ESCOM / IPN
### Desarrollo de Aplicaciones Móviles Nativas · Examen Extraordinario 2020

---

## 🏫 Datos del Proyecto

| Campo | Detalle |
|---|---|
| **Institución** | Instituto Politécnico Nacional (IPN) |
| **Escuela** | Escuela Superior de Cómputo (ESCOM) |
| **Programa** | Ingeniería en Sistemas Computacionales |
| **Materia** | Desarrollo de Aplicaciones Móviles Nativas |
| **Tipo** | Examen Extraordinario 2020 |
| **Lenguaje** | Kotlin |
| **UI** | XML Views / Layouts (Android Nativo) |
| **Min SDK** | API 23 (Android 6.0) |

---

## 📋 Descripción General

**BlueTube** es una aplicación Android nativa que permite a un dispositivo **sin conexión a Internet** reproducir videos de YouTube, utilizando otro dispositivo **con Internet** como servidor intermedio. La comunicación entre ambos dispositivos se realiza exclusivamente mediante **Bluetooth clásico (RFCOMM)**.

---

## 🏗️ Arquitectura Cliente-Servidor

```
┌─────────────────────────────┐         ┌─────────────────────────────┐
│   DISPOSITIVO A — Servidor  │         │   DISPOSITIVO B — Cliente   │
│   (CON Internet)            │         │   (SIN Internet)            │
│                             │         │                             │
│  ┌─────────────────────┐   │         │  ┌─────────────────────┐   │
│  │  WebView YouTube    │   │         │  │  Buscador de videos │   │
│  │  (navega y busca)   │   │         │  │  Reproductor WebView│   │
│  └─────────────────────┘   │         │  │  Controles nativos  │   │
│           │                 │         │  └─────────────────────┘   │
│  ┌─────────────────────┐   │◄───────►│  ┌─────────────────────┐   │
│  │  BluetoothServer    │   │Bluetooth│  │  BluetoothClient    │   │
│  │  RFCOMM Socket      │   │  JSON   │  │  RFCOMM Socket      │   │
│  │  UUID: SPP estándar │   │         │  │  UUID: SPP estándar │   │
│  └─────────────────────┘   │         │  └─────────────────────┘   │
│                             │         │                             │
│  Cache de URLs enviadas     │         │  Historial local            │
│  Log de actividad           │         │  Favoritos locales          │
└─────────────────────────────┘         └─────────────────────────────┘
```

---

## 📁 Estructura del Proyecto

```
app/src/main/
├── java/com/example/escomextra/
│   ├── ModoActivity.kt        ← Pantalla inicial (elegir Servidor o Cliente)
│   ├── ServidorActivity.kt    ← Modo Servidor: WebView + Bluetooth Server
│   ├── ClienteActivity.kt     ← Modo Cliente: Buscador + Reproductor + BT Client
│   └── BluetoothService.kt    ← UUID y constantes Bluetooth compartidas
│
└── res/
    ├── layout/
    │   ├── activity_main.xml          ← Pantalla de selección de modo
    │   ├── activity_servidor.xml      ← Layout del Servidor
    │   ├── activity_cliente.xml       ← Layout del Cliente
    │   └── item_video.xml             ← Item de lista de videos
    ├── values/
    │   ├── colors.xml                 ← Paleta de colores IPN/ESCOM
    │   └── themes.xml                 ← Tema claro con colores IPN
    └── values-night/
        └── themes.xml                 ← Tema oscuro (Modo Noche)
```

---

## 🔧 Clases Principales

### `ModoActivity.kt`
Pantalla de bienvenida. Permite al usuario elegir el rol del dispositivo (Servidor o Cliente) y alternar entre **Modo Claro y Modo Noche**.

### `ServidorActivity.kt`
- Abre un `BluetoothServerSocket` con UUID SPP estándar (`00001101-0000-1000-8000-00805F9B34FB`)
- Inicia un `WebView` que carga `youtube.com` directamente como navegador
- Escucha mensajes JSON del cliente por Bluetooth (búsquedas)
- Cuando el usuario presiona **"ENVIAR ESTE VIDEO AL CLIENTE"**, obtiene la URL actual del WebView y la manda por Bluetooth como mensaje JSON
- Implementa un sistema de **caché** para URLs ya enviadas
- Muestra un **log de actividad** en tiempo real

### `ClienteActivity.kt`
- Se conecta al servidor via `BluetoothSocket` (RFCOMM)
- Implementa **reconexión automática** si se pierde la conexión (reintenta cada 3 segundos)
- Envía búsquedas de texto al servidor por Bluetooth
- Recibe la URL del video y la reproduce en un `WebView` local
- Controla la reproducción via JavaScript sobre el WebView
- Gestiona **historial** y **favoritos** de forma local
- Muestra **advertencia** si la URL recibida no es de YouTube (fuente no segura)

### `BluetoothService.kt`
Objeto singleton con el UUID RFCOMM compartido y el nombre del servidor Bluetooth.

---

## 📡 Protocolo de Comunicación Bluetooth

Todos los mensajes son **JSON de una sola línea** terminados en `\n`, enviados por el `OutputStream` del `BluetoothSocket`.

### Mensajes del Cliente → Servidor

| Tipo | Descripción | Campos |
|---|---|---|
| `PING` | Verificar que el servidor responde | `{"tipo":"PING"}` |
| `BUSQUEDA` | Solicitar búsqueda de video | `{"tipo":"BUSQUEDA","query":"nombre del video"}` |

### Mensajes del Servidor → Cliente

| Tipo | Descripción | Campos |
|---|---|---|
| `PONG` | Respuesta al PING | `{"tipo":"PONG"}` |
| `STREAM_URL` | URL del video seleccionado | `{"tipo":"STREAM_URL","url":"https://youtube.com/watch?v=..."}` |

---

## ✅ Requisitos Técnicos Implementados

### 1. Arquitectura Cliente-Servidor
- ✅ Dispositivo A actúa como punto de acceso a Internet para el cliente
- ✅ Procesa solicitudes de búsqueda recibidas vía Bluetooth
- ✅ Obtiene y devuelve la URL del video al cliente
- ✅ Sistema de caché para URLs ya enviadas
- ✅ Dispositivo B permanece aislado de Internet (solo usa Bluetooth)
- ✅ Interfaz de buscador y reproductor en XML
- ✅ Gestión de historial de videos local

### 2. Comunicación Bluetooth
- ✅ Protocolo RFCOMM (Radio Frequency Communication) — Bluetooth Clásico
- ✅ UUID SPP estándar: `00001101-0000-1000-8000-00805F9B34FB`
- ✅ Mensajes JSON serializados por líneas
- ✅ Reconexión automática en caso de pérdida de conexión (cada 3 segundos)
- ✅ Indicadores visuales del estado de conexión (colores: verde/amarillo/rojo)
- ✅ Notificaciones Toast de conexión y desconexión
- ✅ Compatible con Android 6.0 hasta Android 14+ (permisos dinámicos según versión)

### 3. Características del Reproductor (Cliente)
- ✅ Barra de búsqueda por texto/palabras clave
- ✅ Control Play / Pausar (via JavaScript sobre WebView)
- ✅ Adelantar +10 segundos
- ✅ Retroceder -10 segundos
- ✅ Barra de progreso (SeekBar) sincronizada con el video
- ✅ Sistema de favoritos local
- ✅ Historial de videos reproducidos (persistente en sesión)
- ✅ Modo bajo consumo (checkbox)

### 4. Seguridad
- ✅ Advertencia cuando el contenido proviene de una fuente no verificada (no es YouTube)
- ✅ Modo de privacidad: no registra el video en el historial cuando está activo

---

## 🎨 Diseño Visual

### Paleta de Colores IPN / ESCOM

| Color | Hex | Uso |
|---|---|---|
| Guinda IPN | `#6B1C2D` | Headers, botones primarios |
| Guinda oscuro | `#4A1020` | Fondos de cabecera, estado conexión |
| Guinda claro | `#8B2D42` | Tema noche primario |
| Oro IPN | `#C8A94A` | Acentos, botones secundarios, iconos |
| Oro claro | `#E8C96A` | Textos sobre guinda |
| Azul ESCOM | `#1A237E` | Secciones del cliente |
| Azul claro ESCOM | `#3949AB` | Variante del azul ESCOM |

### Modo Noche
La app soporta **tema claro y oscuro** alternables desde la pantalla principal mediante el botón 🌙/☀️. Utiliza `AppCompatDelegate.setDefaultNightMode()` y un directorio `res/values-night/` con el tema oscuro.

---

## 🚀 Instrucciones de Uso

### Preparación
1. Instalar la **misma APK** en ambos dispositivos (Servidor y Cliente)
2. Vincular (parear) ambos dispositivos por Bluetooth desde Ajustes del sistema
3. Asegurarse de que el Servidor tenga Internet activo (Wi-Fi o datos)

### Dispositivo A — Servidor
1. Abrir BlueTube → seleccionar **"MODO SERVIDOR"**
2. Pulsar **"Visible"** → aceptar el diálogo (300 segundos de visibilidad)
3. Pulsar **"Iniciar"** → esperar a que diga "Esperando cliente..."
4. Navegar YouTube en el WebView que aparece (buscar el video deseado)
5. Cuando el video esté en pantalla → pulsar **"ENVIAR ESTE VIDEO AL CLIENTE"**

### Dispositivo B — Cliente
1. Abrir BlueTube → seleccionar **"MODO CLIENTE"**
2. Pulsar **"Conectar"** → seleccionar el Servidor de la lista
3. Esperar confirmación de conexión (estado en verde ✅)
4. Escribir en el buscador → pulsar **"Go"** (el servidor abrirá esa búsqueda en su YouTube)
5. Esperar a que el Servidor envíe el video → se abrirá automáticamente en el reproductor

---

## 🛠️ Tecnologías Utilizadas

| Tecnología | Versión | Uso |
|---|---|---|
| Kotlin | 1.9.x | Lenguaje principal |
| Android SDK | API 34 | Target SDK |
| Min SDK | API 23 | Android 6.0+ |
| Bluetooth RFCOMM | Android SDK | Comunicación inalámbrica |
| WebView | Android SDK | Reproducción de YouTube |
| Kotlin Coroutines | 1.7.3 | Operaciones asíncronas (Bluetooth, red) |
| Material Components | 1.12.0 | UI y estilos |
| AppCompat | 1.7.0 | Compatibilidad y modo noche |
| Gradle | Kotlin DSL | Sistema de build |
| JSON (org.json) | Android SDK | Protocolo de mensajes Bluetooth |

---

## ⚠️ Limitaciones Técnicas

1. **Stream de video**: YouTube restringe activamente el acceso directo a los archivos de video desde apps externas en 2026 (bloqueo de APIs como PipedAPI y NewPipeExtractor). Por esta razón, el cliente reproduce el video via WebView, lo que requiere conexión a Internet para cargar el stream de YouTube directamente desde sus servidores.

2. **Ancho de banda Bluetooth**: Bluetooth Clásico (RFCOMM) ofrece aproximadamente 700 Kbps de ancho de banda real, insuficiente para transmitir video en tiempo real con calidad aceptable.

3. **Audio + Video simultáneo**: La transmisión simultánea de audio y video comprimido por Bluetooth requeriría codecs especializados (como A2DP para audio) que no están disponibles en el perfil RFCOMM.

---

## 📱 Permisos de la App

```xml
<!-- Internet (solo Servidor) -->
<uses-permission android:name="android.permission.INTERNET"/>

<!-- Bluetooth Android 6-11 -->
<uses-permission android:name="android.permission.BLUETOOTH"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>

<!-- Bluetooth Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE"/>
```

---

## 👨‍💻 Desarrollado con

- Android Studio Hedgehog / Iguana
- Kotlin Coroutines para manejo asíncrono de Bluetooth
- Arquitectura basada en Activities (sin Jetpack Compose, como requiere el examen)
- Patrón de comunicación: JSON sobre RFCOMM Bluetooth
- Diseño Material 3 con paleta institucional IPN/ESCOM

---

*Instituto Politécnico Nacional · Escuela Superior de Cómputo · 2020*
