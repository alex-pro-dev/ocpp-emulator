package com.monta.ocpp.emulator.common.util

import java.io.File

val appRoot by lazy {
    File(System.getProperty("user.home"), ".evcharge-ocpp-emulator").apply {
        mkdirs()
    }
}
