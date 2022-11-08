package com.bromano.instrumentsgecko

import java.util.concurrent.TimeUnit

class Logger {
    companion object {
        fun <T> timedLog(message: String, block: () -> T): T {
            val startTime = System.currentTimeMillis()
            println("$message...")
            val result = block()
            println("$message... [Done] ${humanReadableTime(System.currentTimeMillis() - startTime)}")
            return result
        }

        private fun humanReadableTime(millisTotal: Long): String {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millisTotal)
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millisTotal) - minutes * 60
            val millis = millisTotal % 1000
            return StringBuilder().apply {
                if (minutes > 0) {
                    append("${minutes}m ")
                }
                if (seconds > 0) {
                    append("${seconds}s ")
                }
                if (minutes == 0L) {
                    append("${millis}ms")
                }
            }.toString()
        }
    }
}