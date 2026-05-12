package com.yapt.planttracker.data.backup

import kotlinx.serialization.json.Json

val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
