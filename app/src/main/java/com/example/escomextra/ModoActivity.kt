package com.example.escomextra

import android.Manifest
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat

class ModoActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var modoNocheActivo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bm.adapter

        solicitarPermisos()
        activarBluetooth()

        // Modo noche toggle
        val btnModoNoche = findViewById<Button>(R.id.btnModoNoche)
        btnModoNoche.setOnClickListener {
            modoNocheActivo = !modoNocheActivo
            if (modoNocheActivo) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                btnModoNoche.text = "☀️ Modo Claro"
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                btnModoNoche.text = "🌙 Modo Noche"
            }
        }

        findViewById<Button>(R.id.btnServidor).setOnClickListener {
            startActivity(Intent(this, ServidorActivity::class.java))
        }

        findViewById<Button>(R.id.btnCliente).setOnClickListener {
            startActivity(Intent(this, ClienteActivity::class.java))
        }
    }

    private fun activarBluetooth() {
        if (!bluetoothAdapter.isEnabled && tienePermiso()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun solicitarPermisos() {
        val permisos = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos += listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            permisos += listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permisos += Manifest.permission.INTERNET
        ActivityCompat.requestPermissions(this, permisos.toTypedArray(), 1)
    }

    private fun tienePermiso(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        else true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Se necesitan permisos Bluetooth", Toast.LENGTH_LONG).show()
        }
    }
}
