package com.tamas.wearbluetoothmanager

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private data class FoundDevice(val device: BluetoothDevice, var source: String, var displayName: String?)

    private val found = linkedMapOf<String, FoundDevice>()
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var bleButton: Button
    private lateinit var classicButton: Button
    private lateinit var stopButton: Button
    private var adapter: BluetoothAdapter? = null
    private var leScanner: BluetoothLeScanner? = null
    private var receiverRegistered = false
    private var bleRunning = false
    private var classicRunning = false
    private val handler = Handler(Looper.getMainLooper())

    // Device discoveries can arrive many times per second. Batch UI work to avoid
    // repeatedly rebuilding the entire Wear OS screen for every advertisement.
    private val renderRunnable = Runnable { renderDevices() }
    private fun scheduleRender() {
        handler.removeCallbacks(renderRunnable)
        handler.postDelayed(renderRunnable, 450L)
    }

    private val bleTimeout = Runnable {
        stopBleScan()
        updateControls()
        refreshStatus("BLE keresés kész · ${found.size} eszköz")
        scheduleRender()
    }

    private val classicTimeout = Runnable {
        stopClassicScan()
        updateControls()
        refreshStatus("Classic keresés időkorlátja lejárt · ${found.size} eszköz")
        scheduleRender()
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDevice(result.device, "BLE", result.scanRecord?.deviceName)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addDevice(it.device, "BLE", it.scanRecord?.deviceName) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                bleRunning = false
                handler.removeCallbacks(bleTimeout)
                updateControls()
                refreshStatus("BLE keresési hiba: $errorCode")
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val broadcastName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    device?.let {
                        addDevice(it, if (intent.action == BluetoothDevice.ACTION_NAME_CHANGED) "Név frissítve" else "Classic", broadcastName)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    classicRunning = false
                    handler.removeCallbacks(classicTimeout)
                    updateControls()
                    refreshStatus("Classic keresés kész · ${found.size} eszköz")
                    scheduleRender()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> scheduleRender()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
        buildUi()
        registerBluetoothReceiver()
        if (hasPermissions()) refreshPairedDevices() else requestBluetoothPermissions()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(22))
            setBackgroundColor(0xFF000000.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Wear Bluetooth\nManager"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, match())

        status = TextView(this).apply {
            text = "Készen áll"
            textSize = 12f
            setTextColor(0xFFBDBDBD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(status, match())

        bleButton = Button(this).apply {
            text = "BLE KERESÉS"
            isAllCaps = false
            setOnClickListener { beginBleScan() }
        }
        root.addView(bleButton, match())

        classicButton = Button(this).apply {
            text = "CLASSIC KERESÉS"
            isAllCaps = false
            setOnClickListener { beginClassicScan() }
        }
        root.addView(classicButton, match())

        stopButton = Button(this).apply {
            text = "KERESÉS LEÁLLÍTÁSA"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { stopAllScans("Keresés kézzel leállítva") }
        }
        root.addView(stopButton, match())

        root.addView(TextView(this).apply {
            text = "Talált eszközök"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(14), 0, dp(7))
        }, match())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, match())

        root.addView(TextView(this).apply {
            text = "A BLE és a Classic keresés külön indítható. A Classic keresés rövidebb ideig fut, mert jobban terhelheti az órát. Bármikor leállíthatod."
            textSize = 10f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, dp(14), 0, 0)
        }, match())
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(btReceiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(btReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun requiredPermissions() = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private fun hasPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() = requestPermissions(requiredPermissions(), 100)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && hasPermissions()) refreshPairedDevices()
        else refreshStatus("A Bluetooth- és helyengedélyek szükségesek a kereséshez.")
    }

    private fun readyAdapter(): BluetoothAdapter? {
        if (!hasPermissions()) {
            requestBluetoothPermissions()
            return null
        }
        val bt = adapter
        if (bt == null) {
            refreshStatus("Ez az eszköz nem rendelkezik Bluetooth-adapterrel.")
            return null
        }
        if (!bt.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            refreshStatus("Kapcsold be a Bluetooth-t, majd indítsd újra a keresést.")
            return null
        }
        return bt
    }

    private fun beginBleScan() {
        val bt = readyAdapter() ?: return
        stopAllScans(null)
        try {
            found.clear()
            renderDevices()
            leScanner = bt.bluetoothLeScanner
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setReportDelay(0L)
                .build()
            leScanner?.startScan(null, settings, leCallback)
            bleRunning = true
            handler.postDelayed(bleTimeout, 20_000L)
            updateControls()
            refreshStatus("BLE keresés folyamatban…")
        } catch (e: SecurityException) {
            bleRunning = false
            updateControls()
            refreshStatus("Bluetooth-jogosultság hiányzik: ${e.message}")
        } catch (e: Exception) {
            bleRunning = false
            updateControls()
            refreshStatus("BLE keresési hiba: ${e.message}")
        }
    }

    private fun beginClassicScan() {
        val bt = readyAdapter() ?: return
        stopAllScans(null)
        try {
            found.clear()
            renderDevices()
            if (bt.isDiscovering) bt.cancelDiscovery()
            val started = bt.startDiscovery()
            if (!started) {
                refreshStatus("A Classic keresést nem sikerült elindítani.")
                updateControls()
                return
            }
            classicRunning = true
            handler.postDelayed(classicTimeout, 12_000L)
            updateControls()
            refreshStatus("Classic keresés folyamatban… (legfeljebb 12 mp)")
        } catch (e: SecurityException) {
            classicRunning = false
            updateControls()
            refreshStatus("Bluetooth-jogosultság hiányzik: ${e.message}")
        } catch (e: Exception) {
            classicRunning = false
            updateControls()
            refreshStatus("Classic keresési hiba: ${e.message}")
        }
    }

    private fun stopBleScan() {
        handler.removeCallbacks(bleTimeout)
        try { leScanner?.stopScan(leCallback) } catch (_: Exception) { }
        bleRunning = false
    }

    private fun stopClassicScan() {
        handler.removeCallbacks(classicTimeout)
        try { if (adapter?.isDiscovering == true) adapter?.cancelDiscovery() } catch (_: Exception) { }
        classicRunning = false
    }

    private fun stopAllScans(message: String?) {
        stopBleScan()
        stopClassicScan()
        updateControls()
        if (message != null) {
            refreshStatus("$message · ${found.size} eszköz")
            scheduleRender()
        }
    }

    private fun updateControls() {
        if (!::bleButton.isInitialized) return
        bleButton.isEnabled = !bleRunning && !classicRunning
        classicButton.isEnabled = !bleRunning && !classicRunning
        stopButton.isEnabled = bleRunning || classicRunning
    }

    private fun addDevice(device: BluetoothDevice, source: String, advertisedName: String? = null) {
        runOnUiThread {
            try {
                val key = device.address ?: return@runOnUiThread
                val systemName = try { device.name } catch (_: SecurityException) { null }
                val candidateName = advertisedName?.takeIf { it.isNotBlank() }
                    ?: systemName?.takeIf { it.isNotBlank() }
                val old = found[key]
                if (old == null) found[key] = FoundDevice(device, source, candidateName)
                else {
                    if (!old.source.contains(source)) old.source += " + $source"
                    if (!candidateName.isNullOrBlank()) old.displayName = candidateName
                }
                scheduleRender()
            } catch (_: SecurityException) {
                refreshStatus("A Bluetooth Connect engedély hiányzik.")
            }
        }
    }

    private fun refreshPairedDevices() {
        if (!hasPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { device ->
                val name = try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
                val existing = found[device.address]
                if (existing == null) found[device.address] = FoundDevice(device, "Párosított", name)
                else {
                    existing.displayName = name ?: existing.displayName
                    if (!existing.source.contains("Párosított")) existing.source += " + Párosított"
                }
            }
            renderDevices()
        } catch (_: SecurityException) { }
    }

    private fun renderDevices() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        if (found.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Még nincs találat. Indíts BLE vagy Classic keresést."
                textSize = 12f
                setTextColor(0xFFBDBDBD.toInt())
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        found.values.forEach { item ->
            val device = item.device
            val name = item.displayName?.takeIf { it.isNotBlank() }
                ?: try { device.name?.takeIf { it.isNotBlank() } ?: "Név még nem érhető el" }
                catch (_: SecurityException) { "Bluetooth eszköz" }
            val address = try { device.address } catch (_: SecurityException) { "Nem elérhető" }
            val bond = try {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> "Párosítva"
                    BluetoothDevice.BOND_BONDING -> "Párosítás…"
                    else -> "Nincs párosítva"
                }
            } catch (_: SecurityException) { "Állapot nem elérhető" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(0xFF171717.toInt())
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
            })
            row.addView(TextView(this).apply {
                text = address
                textSize = 10f
                setTextColor(0xFF80CBC4.toInt())
            })
            row.addView(TextView(this).apply {
                text = "$bond · ${item.source}"
                textSize = 10f
                setTextColor(0xFFB0B0B0.toInt())
            })
            val pair = Button(this).apply {
                text = if (bond == "Párosítva") "PÁROSÍTVA" else "PÁROSÍTÁS"
                isEnabled = bond == "Nincs párosítva"
                isAllCaps = false
                setOnClickListener { pairDevice(device) }
            }
            row.addView(pair)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(7)
            list.addView(row, params)
        }
    }

    private fun pairDevice(device: BluetoothDevice) {
        if (!hasPermissions()) {
            requestBluetoothPermissions()
            return
        }
        try {
            stopAllScans(null)
            val started = device.createBond()
            Toast.makeText(this, if (started) "Párosítási kérés elküldve" else "A párosítás nem indult el", Toast.LENGTH_SHORT).show()
            refreshStatus("Várakozás a rendszer párosítási ablakára…")
        } catch (e: SecurityException) {
            refreshStatus("Párosítási jogosultsági hiba: ${e.message}")
        }
    }

    private fun refreshStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    override fun onDestroy() {
        handler.removeCallbacks(renderRunnable)
        stopBleScan()
        stopClassicScan()
        if (receiverRegistered) try { unregisterReceiver(btReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
