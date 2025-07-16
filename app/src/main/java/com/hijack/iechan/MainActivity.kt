package com.hijack.iechan

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1
    }

    private var overlayToast: Toast? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isOverlayShowing = false

    private var selectedAppPackage: String? = null
    private var selectedActivity: ComponentName? = null

    private var posX = 0
    private var posY = 100

    private var toastThread: Thread? = null
    private var isToastLoopActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val startOverlayBtn = findViewById<Button>(R.id.startOverlayBtn)
        val stopOverlayBtn = findViewById<Button>(R.id.stopOverlayBtn)
        val launchActivityBtn = findViewById<Button>(R.id.launchActivityBtn)

        val posXInput = findViewById<EditText>(R.id.posXInput)
        val posYInput = findViewById<EditText>(R.id.posYInput)

        startOverlayBtn.setOnClickListener {
            posX = posXInput.text.toString().toIntOrNull() ?: 0
            posY = posYInput.text.toString().toIntOrNull() ?: 100

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    showOverlayButton()
                } else {
                    requestOverlayPermission()
                }
            } else {
                showOverlayButton()
            }
        }

        stopOverlayBtn.setOnClickListener {
            hideOverlayButton()
        }

        launchActivityBtn.setOnClickListener {
            selectedActivity?.let {
                try {
                    val intent = Intent().apply {
                        component = it
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Impossible de lancer l'activité", Toast.LENGTH_SHORT).show()
                }
            }
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        setupAppSelector()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                showOverlayButton()
            } else {
                Toast.makeText(this, "Permission refusée", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showOverlayButton() {
        if (isOverlayShowing) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showCustomToastOverlay()
        } else {
            showWindowOverlay()
        }

        isOverlayShowing = true
    }

    private fun showCustomToastOverlay() {
        startToastLoop()
    }

    private fun startToastLoop() {
        isToastLoopActive = true
        toastThread = thread {
            try {
                while (isToastLoopActive) {
                    runOnUiThread {
                        val inflater = layoutInflater
                        val layout = inflater.inflate(R.layout.custom_toast_layout, null)
                        val overlayButton = layout.findViewById<Button>(R.id.overlayButton)
                        overlayButton.setOnClickListener {
                            Toast.makeText(this, "Bouton superposé cliqué!", Toast.LENGTH_SHORT).show()
                        }

                        overlayToast = Toast(applicationContext).apply {
                            setGravity(Gravity.TOP or Gravity.RIGHT, posX, posY)
                            duration = Toast.LENGTH_LONG
                            view = layout
                            show()
                        }
                    }
                    Thread.sleep(3500)
                }
            } catch (e: InterruptedException) {
                // Fin propre
            }
        }
    }

    private fun stopToastLoop() {
        isToastLoopActive = false
        toastThread?.interrupt()
        toastThread = null
        overlayToast?.cancel()
        overlayToast = null
    }

    private fun showWindowOverlay() {
        if (overlayView != null) return

        overlayView = LayoutInflater.from(this).inflate(R.layout.custom_toast_layout, null)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = posX
            y = posY
        }

        val overlayButton = overlayView?.findViewById<Button>(R.id.overlayButton)
        overlayButton?.setOnClickListener {
            Toast.makeText(this, "Bouton superposé cliqué!", Toast.LENGTH_SHORT).show()
        }

        windowManager?.addView(overlayView, overlayParams)
    }

    private fun hideOverlayButton() {
        isOverlayShowing = false
        stopToastLoop()

        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
            overlayParams = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlayButton()
    }

    private fun setupAppSelector() {
        val spinner = findViewById<Spinner>(R.id.appSelector)
        val pm = packageManager

        val launchableApps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }

        val appNames = launchableApps.map { it.loadLabel(pm).toString() }
        val appPackages = launchableApps.map { it.packageName }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, appNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                selectedAppPackage = appPackages[position]

                val activities = getExportedActivities(selectedAppPackage!!)
                val activitySpinner = findViewById<Spinner>(R.id.activitySelector)
                val activityNames = activities.map { it.className }

                val actAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, activityNames)
                actAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                activitySpinner.adapter = actAdapter

                activitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, id: Long) {
                        selectedActivity = activities[pos]
                    }

                    override fun onNothingSelected(p0: AdapterView<*>?) {}
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun getExportedActivities(packageName: String): List<ComponentName> {
        val pm = packageManager
        val exportedActivities = mutableListOf<ComponentName>()

        try {
            val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            pkgInfo.activities?.forEach { activity ->
                if (activity.exported) {
                    exportedActivities.add(ComponentName(packageName, activity.name))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return exportedActivities
    }
}
