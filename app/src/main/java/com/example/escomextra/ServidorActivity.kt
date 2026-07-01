package com.example.escomextra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.util.*

class ServidorActivity : AppCompatActivity() {

    private val TAG = "BlueTube_Servidor"

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var serverSocket: BluetoothServerSocket? = null
    private var outputStream: OutputStream? = null
    private var servidorCorriendo = false

    private lateinit var tvEstado: TextView
    private lateinit var webViewYT: WebView
    private lateinit var btnEnviar: Button
    private lateinit var btnVisible: Button
    private lateinit var btnIniciar: Button

    // Cache: guarda URLs ya enviadas para no repetir
    private val cache = mutableMapOf<String, String>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_servidor)

        tvEstado   = findViewById(R.id.tvEstado)
        webViewYT  = findViewById(R.id.webViewYT)
        btnEnviar  = findViewById(R.id.btnEnviarCliente)
        btnVisible = findViewById(R.id.btnVisible)
        btnIniciar = findViewById(R.id.btnIniciar)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        configurarWebView()
        btnVisible.setOnClickListener { hacerseVisible() }
        btnIniciar.setOnClickListener { iniciarServidor() }

        btnEnviar.setOnClickListener {
            val url = webViewYT.url ?: ""
            if (url.isEmpty() || !url.contains("watch")) {
                Toast.makeText(this, "Abre un video primero en YouTube", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (outputStream == null) {
                Toast.makeText(this, "No hay cliente conectado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Usar cache si ya se envió esta URL antes
            val cached = cache[url]
            if (cached != null) {
                Log.d(TAG, "Usando cache para: $url")
            }
            enviarUrlAlCliente(url)
        }
    }

    private fun configurarWebView() {
        webViewYT.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        webViewYT.webChromeClient = WebChromeClient()
        webViewYT.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("watch")) {
                    setEstado("Video listo — pulsa ENVIAR cuando quieras")
                } else {
                    setEstado("Navega YouTube y selecciona un video")
                }
            }
        }
        webViewYT.loadUrl("https://www.youtube.com")
    }

    private fun hacerseVisible() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        if (tienePermiso()) startActivity(intent)
    }

    private fun iniciarServidor() {
        if (servidorCorriendo) {
            Toast.makeText(this, "Ya está corriendo", Toast.LENGTH_SHORT).show()
            return
        }
        servidorCorriendo = true
        btnIniciar.isEnabled = false
        setEstado("Iniciando servidor...")

        scope.launch {
            try {
                serverSocket = if (tienePermiso())
                    bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        BluetoothService.NOMBRE_SERVIDOR, BluetoothService.UUID_APP)
                else { servidorCorriendo = false; return@launch }

                setEstado("Esperando cliente...")

                while (servidorCorriendo) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: IOException) { break } ?: break

                    val nombre = if (tienePermiso()) socket.remoteDevice?.name ?: "?" else "?"
                    outputStream = socket.outputStream
                    setEstado("Cliente conectado: $nombre")
                    escucharCliente(socket)
                }
            } catch (e: Exception) {
                setEstado("Error: ${e.message}")
            } finally {
                servidorCorriendo = false
                withContext(Dispatchers.Main) { btnIniciar.isEnabled = true }
            }
        }
    }

    private suspend fun escucharCliente(socket: BluetoothSocket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        try {
            while (true) {
                val linea = reader.readLine() ?: break
                val msg = JSONObject(linea)
                when (msg.optString("tipo")) {
                    "PING" -> {
                        outputStream?.write((JSONObject().apply {
                            put("tipo", "PONG")
                        }.toString() + "\n").toByteArray())
                        outputStream?.flush()
                    }
                    "BUSQUEDA" -> {
                        val query = msg.getString("query")
                        val url = "https://www.youtube.com/results?search_query=" +
                                java.net.URLEncoder.encode(query, "UTF-8")
                        withContext(Dispatchers.Main) {
                            webViewYT.loadUrl(url)
                            setEstado("Buscando: $query")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cliente desconectado: ${e.message}")
        } finally {
            runCatching { socket.close() }
            outputStream = null
            setEstado("Cliente desconectado — esperando...")
        }
    }

    private fun enviarUrlAlCliente(url: String) {
        // Guardar en cache
        cache[url] = url
        scope.launch {
            try {
                val msg = JSONObject().apply {
                    put("tipo", "STREAM_URL")
                    put("url", url)
                }
                outputStream?.write((msg.toString() + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ServidorActivity,
                        "✅ Video enviado al cliente por Bluetooth", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ServidorActivity,
                        "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setEstado(txt: String) = runOnUiThread { tvEstado.text = txt }

    private fun tienePermiso() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    else true

    override fun onBackPressed() {
        if (webViewYT.canGoBack()) webViewYT.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        servidorCorriendo = false
        scope.cancel()
        runCatching { serverSocket?.close() }
    }
}
