package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Creates a single-thread [ExecutorService] backed by one daemon thread named [name].
 *
 * Used by the log writer and the HTTP channels to serialize their work off the main
 * thread (so broadcast `onReceive` callbacks stay snappy) without keeping the JVM alive.
 */
internal fun singleThreadDaemonExecutor(name: String): ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
        }
    }
