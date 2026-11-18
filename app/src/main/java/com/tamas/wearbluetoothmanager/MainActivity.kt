package com.tamas.wearbluetoothmanager

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private data class FoundDevice(val device: BluetoothDevice, var source: String)

    private val found = linkedMapOf<String, FoundDevice>()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var scanButton: Button
    private var adapter: BluetoothAdapter? = null
    private var leScanner: BluetoothLeScanner? = null
    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    private val stopBle = Runnable {
        try { leScanner?.stopScan(leCallback) } catch (_: SecurityException) { }
        refreshStatus("Keresés befejezve · ${found.size} eszköz")
        renderDevices()
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addDevice(result.device, "BLE")
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addDevice(it.device, "BLE") }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { refreshStatus("BLE keresési hiba: $errorCode") }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { addDevice(it, "Classic") }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    refreshStatus("Classic keresés kész · ${found.size} eszköz")
                    renderDevices()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> renderDevices()
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(0xFF000000.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Wear Bluetooth\nManager"
            textSize = 23f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, match())

        status = TextView(this).apply {
            text = "Készen áll"
            textSize = 12f
            setTextColor(0xFFBDBDBD.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(status, match())

        scanButton = Button(this).apply {
            text = "ESZKÖZÖK KERESÉSE"
            isAllCaps = false
            setOnClickListener { beginScan() }
        }
        root.addView(scanButton, match())

        root.addView(TextView(this).apply {
            text = "Talált eszközök"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF80CBC4.toInt())
            setPadding(0, dp(18), 0, dp(8))
        }, match())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list, match())

        root.addView(TextView(this).apply {
            text = "Tipp: a billentyűzetet párosítási módba kell kapcsolni. A keresés Classic és BLE eszközöket is listáz."
            textSize = 11f
            setTextColor(0xFF9E9E9E.toInt())
            setPadding(0, dp(18), 0, 0)
        }, match())
    }

    private fun registerBluetoothReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
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

    private fun requestBluetoothPermissions() {
        requestPermissions(requiredPermissions(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && hasPermissions()) refreshPairedDevices()
        else refreshStatus("A Bluetooth- és helyengedélyek szükségesek a kereséshez.")
    }

    private fun beginScan() {
        if (!hasPermissions()) {
            requestBluetoothPermissions()
            return
        }
        val bt = adapter
        if (bt == null) {
            refreshStatus("Ez az eszköz nem rendelkezik Bluetooth-adapterrel.")
            return
        }
        if (!bt.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            refreshStatus("Kapcsold be a Bluetooth-t, majd indítsd újra a keresést.")
            return
        }

        found.clear()
        renderDevices()
        scanButton.isEnabled = false
        refreshStatus("Keresés folyamatban…")
        try {
            if (bt.isDiscovering) bt.cancelDiscovery()
            val classicStarted = bt.startDiscovery()
            leScanner = bt.bluetoothLeScanner
            leScanner?.startScan(leCallback)
            handler.removeCallbacks(stopBle)
            handler.postDelayed(stopBle, 15_000L)
            if (!classicStarted) refreshStatus("BLE keresés fut; Classic keresés nem indult el.")
        } catch (e: SecurityException) {
            refreshStatus("Nincs Bluetooth-jogosultság: ${e.message}")
        } catch (e: Exception) {
            refreshStatus("Keresési hiba: ${e.message}")
        }
        handler.postDelayed({ scanButton.isEnabled = true }, 15_500L)
    }

    private fun addDevice(device: BluetoothDevice, source: String) {
        runOnUiThread {
            try {
                val key = device.address ?: return@runOnUiThread
                val old = found[key]
                if (old == null) found[key] = FoundDevice(device, source)
                else if (!old.source.contains(source)) old.source += " + $source"
                renderDevices()
            } catch (_: SecurityException) {
                refreshStatus("A Bluetooth Connect engedély hiányzik.")
            }
        }
    }

    private fun refreshPairedDevices() {
        if (!hasPermissions()) return
        try {
            adapter?.bondedDevices?.forEach { found[it.address] = FoundDevice(it, "Párosított") }
            renderDevices()
        } catch (_: SecurityException) { }
    }

    private fun renderDevices() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        if (found.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Még nincs találat. Tedd a billentyűzetet párosítási módba, majd keress újra."
                textSize = 12f
                setTextColor(0xFFBDBDBD.toInt())
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        found.values.forEach { item ->
            val device = item.device
            val name = try { device.name ?: "Ismeretlen eszköz" } catch (_: SecurityException) { "Bluetooth eszköz" }
            val bond = try {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> "Párosítva"
                    BluetoothDevice.BOND_BONDING -> "Párosítás…"
                    else -> "Nincs párosítva"
                }
            } catch (_: SecurityException) { "Állapot nem elérhető" }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setBackgroundColor(0xFF171717.toInt())
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
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
            params.bottomMargin = dp(8)
            list.addView(row, params)
        }
    }

    private fun pairDevice(device: BluetoothDevice) {
        if (!hasPermissions()) {
            requestBluetoothPermissions()
            return
        }
        try {
            adapter?.cancelDiscovery()
            leScanner?.stopScan(leCallback)
            val started = device.createBond()
            Toast.makeText(this, if (started) "Párosítási kérés elküldve" else "A párosítás nem indult el", Toast.LENGTH_SHORT).show()
            refreshStatus("Várakozás a rendszer párosítási ablakára…")
        } catch (e: SecurityException) {
            refreshStatus("Párosítási jogosultsági hiba: ${e.message}")
        }
    }

    private fun refreshStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopBle)
        try { leScanner?.stopScan(leCallback) } catch (_: Exception) { }
        try { adapter?.cancelDiscovery() } catch (_: Exception) { }
        if (receiverRegistered) try { unregisterReceiver(btReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
