package com.droiddeck.launcher.gpu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
<<<<<<< HEAD
import com.droiddeck.launcher.wayland.WaylandCompositor

class HeadTracker(context: Context, private val rotation: () -> Int) : SensorEventListener {
=======
import com.droiddeck.launcher.glasses.SpatialTracking
import com.droiddeck.launcher.wayland.WaylandCompositor

class HeadTracker(context: Context, private val glasses: Boolean = false, val centerView: Boolean = false,
                  private val rotation: () -> Int) : SensorEventListener {
>>>>>>> upstream/main
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var thread: HandlerThread? = null
<<<<<<< HEAD
=======
    private var started = false
    private val glassesLock = Any()
>>>>>>> upstream/main
    @Volatile private var reference: HeadPose.Reference? = null
    @Volatile private var recenterPending = true
    @Volatile private var latest: FloatArray? = null

<<<<<<< HEAD
=======
    private val glassesSink = com.droiddeck.launcher.glasses.Glasses.PoseSink { x, y, z, w, _ ->
        val m = HeadPose.fromGlasses(x, y, z, w)
        synchronized(glassesLock) {
            if (!started) return@PoseSink
            latest = m
            if (SpatialTracking.enabled) {
                SpatialTracking.session.view(centerView)?.let { pose ->
                    WaylandCompositor.nativeSetCardboardSpatialPose(pose.rotation.matrix(), pose.position.array(),
                        SpatialTracking.session.valid(System.nanoTime()))
                }
            } else apply(m)
        }
    }

    val usesGlasses: Boolean get() = glasses

>>>>>>> upstream/main
    val available: Boolean get() = sensor != null
    val sensorName: String get() = sensor?.let { "${it.name} (${if (it.type == Sensor.TYPE_GAME_ROTATION_VECTOR) "game rotation vector" else "rotation vector"})" } ?: "none"

    fun start() {
<<<<<<< HEAD
=======
        if (glasses) {
            synchronized(glassesLock) {
                if (started) return
                started = true
                recenterPending = true
            }
            com.droiddeck.launcher.glasses.Glasses.setPoseSink(glassesSink)
            WaylandCompositor.nativeLog("cardboard", "head tracking on: VITURE glasses IMU")
            return
        }
>>>>>>> upstream/main
        val s = sensor ?: run {
            WaylandCompositor.nativeLog("cardboard", "no rotation sensor: the virtual screen stays head-locked")
            WaylandCompositor.nativeSetCardboardPose(HeadPose.IDENTITY, false)
            return
        }
        if (thread != null) return
        recenterPending = true
        val t = HandlerThread("droiddeck-headtracker").also { it.start() }
        thread = t
        sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
        WaylandCompositor.nativeLog("cardboard", "head tracking on: $sensorName")
    }

    fun stop() {
<<<<<<< HEAD
=======
        if (glasses) {
            synchronized(glassesLock) {
                if (!started) return
                started = false
                SpatialTracking.publish(System.nanoTime())
                WaylandCompositor.nativeSetCardboardPose(HeadPose.IDENTITY, false)
            }
            com.droiddeck.launcher.glasses.Glasses.clearPoseSink(glassesSink)
            return
        }
>>>>>>> upstream/main
        val t = thread ?: return
        sensors.unregisterListener(this)
        t.quitSafely()
        thread = null
        WaylandCompositor.nativeSetCardboardPose(HeadPose.IDENTITY, false)
    }

    fun recenter() {
<<<<<<< HEAD
        recenterPending = true
        latest?.let { apply(it) }
=======
        if (glasses && SpatialTracking.enabled) { SpatialTracking.recenter(centerView); return }
        recenterPending = true
        if (glasses) synchronized(glassesLock) { if (started) latest?.let { apply(it) } }
        else latest?.let { apply(it) }
>>>>>>> upstream/main
    }

    override fun onSensorChanged(event: SensorEvent) {
        val q = HeadPose.quaternionFromRotationVector(event.values)
        val m = HeadPose.toScreen(HeadPose.fromQuaternion(q[0], q[1], q[2], q[3]), rotation())
        latest = m
        apply(m)
    }

    private fun apply(screenToWorld: FloatArray) {
<<<<<<< HEAD
        val ref = reference.takeUnless { recenterPending } ?: HeadPose.recenter(screenToWorld).also {
=======
        val ref = reference.takeUnless { recenterPending } ?: HeadPose.recenter(screenToWorld, centerView).also {
>>>>>>> upstream/main
            reference = it
            recenterPending = false
        }
        WaylandCompositor.nativeSetCardboardPose(HeadPose.relative(ref, screenToWorld), true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("DroidDeck", "head tracker accuracy $accuracy")
    }
}
