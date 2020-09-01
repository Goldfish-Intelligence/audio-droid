package org.rocstreaming.rocdroid

import android.app.*
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
import android.media.AudioTrack.MODE_STREAM
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import org.rocstreaming.roctoolkit.*


class RocStreamService : Service() {

    companion object {
        const val CHANNEL_ID = "RocStreamServiceChannel"
        const val STREAM_DATA_KEY = "STREAM_DATA_KEY"
        const val STREAM_TOGGLE_KEY = "STREAM_TOGGLE_KEY"
        const val ROC_STREAM_SERVICE_INTENT_STRING = "ROC_STREAM_SERVICE_UPDATE"
        const val ACTION_TOGGLE_MUTE = "ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SEND = "ACTION_TOGGLE_SEND"
        const val ACTION_TOGGLE_RECV = "ACTION_TOGGLE_RECEIVE"
        const val ACTION_UPDATE_STREAM = "ACTION_UPDATE_STREAM"
    }

    private var initialized = false
    private lateinit var recvThread: Thread
    private lateinit var sendThread: Thread
    private lateinit var streamData: StreamData


    // --------------- Roc-Sreaming calls --------------

    /**
     * Start roc sender, is already in separate thread
     */
    private fun startSender(ip: String, audioPort: Int, errorPort: Int) {

        val audioRecord = createAudioRecord()

        audioRecord.startRecording()
        val config = SenderConfig.Builder(
            SAMPLE_RATE,
            ChannelSet.STEREO,
            FrameEncoding.PCM_FLOAT
        ).build()

        Context().use { context ->
            Sender(context, config).use { sender ->
                sender.bind(Address(Family.AUTO, "0.0.0.0", 0))
                sender.connect(
                    PortType.AUDIO_SOURCE,
                    Protocol.RTP_RS8M_SOURCE,
                    Address(Family.AUTO, ip, audioPort)
                )
                sender.connect(
                    PortType.AUDIO_REPAIR,
                    Protocol.RS8M_REPAIR,
                    Address(Family.AUTO, ip, errorPort)
                )

                val samples = FloatArray(BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted) {
                    audioRecord.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    sender.write(samples)
                }
            }
        }

        audioRecord.release()
        if (!recvThread.isAlive) stopService(Intent(this, this.javaClass))
    }

    /**
     * Start roc receiver, is already in separated thread and play samples via audioTrack
     */
    private fun startReceiver(ip: String, audioPort: Int, errorPort: Int) {

        val audioTrack = createAudioTrack()

        audioTrack.play()
        val config = ReceiverConfig.Builder(
            SAMPLE_RATE,
            ChannelSet.STEREO,
            FrameEncoding.PCM_FLOAT
        ).build()

        Context().use { context ->
            Receiver(context, config).use { receiver ->
                receiver.bind(
                    PortType.AUDIO_SOURCE,
                    Protocol.RTP_RS8M_SOURCE,
                    Address(Family.AUTO, ip, audioPort)
                )
                receiver.bind(
                    PortType.AUDIO_REPAIR,
                    Protocol.RS8M_REPAIR,
                    Address(Family.AUTO, ip, errorPort)
                )

                val samples = FloatArray(BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted) {
                    receiver.read(samples)
                    audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                }
            }
        }

        audioTrack.release()
        if (!sendThread.isAlive) stopService(Intent(this, this.javaClass))
    }


    private fun createAudioRecord(): AudioRecord {
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // should be mono
            .build()

        val bufferSize = AudioRecord.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding
        )

        return AudioRecord(
            // https://developer.android.com/reference/kotlin/android/media/MediaRecorder.AudioSource#voice_performance
            // unsure if chosen correctly
            @Suppress
            MediaRecorder.AudioSource.VOICE_PERFORMANCE,
            audioFormat.sampleRate,
            audioFormat.channelMask,
            audioFormat.encoding,
            bufferSize
        )
    }

    private fun createAudioTrack(): AudioTrack {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setFlags(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val bufferSize = AudioTrack.getMinBufferSize(
            audioFormat.sampleRate,
            audioFormat.encoding,
            audioFormat.channelMask
        )

        return AudioTrack(
            audioAttributes,
            audioFormat,
            bufferSize,
            MODE_STREAM,
            AUDIO_SESSION_ID_GENERATE
        )
    }

    // ---------- Android callbacks ----------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!initialized) {
            Log.i(CHANNEL_ID, "Service Start invoked!")
            streamData = intent!!.extras!!.get(STREAM_DATA_KEY) as StreamData

            createNotificationChannel()
            val notificationIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)

            val notificationLayout =
                RemoteViews(applicationContext.packageName, R.layout.notification)

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.presence_audio_online)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(notificationLayout)
                .setFullScreenIntent(pendingIntent, true)
                .build()
            startForeground(1, notification)
            initialized = true
            val br = object : BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    intent?.action?.let {
                        if (intent.hasExtra(STREAM_TOGGLE_KEY))
                            onAction(
                                it,
                                applies = intent.getBooleanExtra(STREAM_TOGGLE_KEY, false)
                            )
                        if (intent.hasExtra(STREAM_DATA_KEY))
                            onAction(
                                it,
                                data = intent.getSerializableExtra(STREAM_DATA_KEY)!! as StreamData
                            )
                    }
                }
            }
            sendThread = Thread()
            recvThread = Thread()

            val filter = IntentFilter(ROC_STREAM_SERVICE_INTENT_STRING).apply {
                addAction(ACTION_TOGGLE_MUTE)
                addAction(ACTION_TOGGLE_SEND)
                addAction(ACTION_TOGGLE_RECV)
                addAction(ACTION_UPDATE_STREAM)
            }

            registerReceiver(br, filter)
        }

        return START_STICKY
    }


    private fun onAction(action: String, data: StreamData? = null, applies: Boolean = false) {
        when (action) {
            ACTION_TOGGLE_RECV -> {
                if (!applies && recvThread.isAlive) recvThread.interrupt()
                if (applies && !recvThread.isAlive) {
                    recvThread = Thread {
                        Runnable {
                            startReceiver(
                                streamData.ip,
                                streamData.portAudioRecv,
                                streamData.portErrorRecv
                            )
                        }
                    }
                    recvThread.start()
                }
            }
            ACTION_TOGGLE_SEND -> {
                if (!applies && sendThread.isAlive) sendThread.interrupt()
                if (applies && !sendThread.isAlive) {
                    sendThread = Thread {
                        Runnable {
                            startSender(
                                streamData.ip,
                                streamData.portAudioSend,
                                streamData.portErrorSend
                            )
                        }
                    }
                    sendThread.start()
                }
            }
            ACTION_TOGGLE_MUTE -> {
                TODO("Mute stream")
            }
            ACTION_UPDATE_STREAM -> {
                val recvChanged = data!!.recvChanged(streamData)
                val sendChanged = data.sendChanged(streamData)
                streamData = data
                if (recvChanged && recvThread.isAlive) {
                    recvThread.interrupt()
                    onAction(ACTION_TOGGLE_RECV, applies = true)
                }
                if (sendChanged && sendThread.isAlive) {
                    sendThread.interrupt()
                    onAction(ACTION_TOGGLE_SEND, applies = true)
                }
            }
            else -> {
                return // why are we here?
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "RocStream Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(serviceChannel)
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        sendThread.interrupt()
        super.onDestroy()
    }

}