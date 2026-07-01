package com.example.escomextra

import java.util.UUID

object BluetoothService {
    // UUID estandar SPP (Serial Port Profile) para RFCOMM
    val UUID_APP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    const val NOMBRE_SERVIDOR = "BlueTubeServer"
}
