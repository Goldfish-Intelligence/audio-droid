package org.rocstreaming.rocdroid

import java.io.Serializable

data class StreamData(
    val ip: String,
    val portAudioRecv: Int,
    val portErrorRecv: Int,
    val portAudioSend: Int,
    val portErrorSend: Int
) : Serializable {
    fun modified(
        ip: String = this.ip,
        portAudioRecv: Int = this.portAudioRecv,
        portErrorRecv: Int = this.portErrorRecv,
        portAudioSend: Int = this.portAudioSend,
        portErrorSend: Int = this.portErrorSend
    ): StreamData = StreamData(ip, portAudioRecv, portErrorRecv, portAudioSend, portErrorSend)

    fun sendChanged(other: StreamData): Boolean =
        other.ip != ip || other.portAudioSend != portAudioSend || other.portErrorSend != portErrorSend

    fun recvChanged(other: StreamData): Boolean =
        other.ip != ip || other.portAudioRecv != portAudioRecv || other.portErrorRecv != portErrorRecv
}

data class TransmissionData(
    val send: Boolean,
    val recv: Boolean,
    val muted: Boolean,
    val deafed: Boolean
) : Serializable

data class ControlData(
    val state: ConnectionState,
    val serverName: String,
    val ClientName: String
) : Serializable