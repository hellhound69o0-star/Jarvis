package com.example.selflearn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.selflearn.firewall.SimpleFirewallService

class FirewallSettingsActivity : AppCompatActivity() {

    companion object {
        private const val VPN_REQUEST_CODE = 300
    }

    private lateinit var apps: List<ApplicationInfo>
    private lateinit var checkedState: BooleanArray

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_firewall_settings)

        val prefs = getSharedPreferences(SimpleFirewallService.PREFS_NAME, Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet(SimpleFirewallService.KEY_BLOCKED_APPS, emptySet()) ?: emptySet()

        val pm = packageManager
        apps = pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        checkedState = BooleanArray(apps.size) { blocked.contains(apps[it].packageName) }

        val listView = findViewById<ListView>(R.id.appListView)
        val labels = apps.map { it.loadLabel(pm).toString() }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, labels) {}
        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        for (i in checkedState.indices) listView.setItemChecked(i, checkedState[i])

        listView.setOnItemClickListener { _, _, position, _ ->
            checkedState[position] = listView.isItemChecked(position)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val blockedPackages = apps.indices.filter { checkedState[it] }.map { apps[it].packageName }.toSet()
            prefs.edit().putStringSet(SimpleFirewallService.KEY_BLOCKED_APPS, blockedPackages).apply()
            findViewById<TextView>(R.id.statusLabel).text =
                "Saved. ${blockedPackages.size} app(s) will be blocked once the firewall is started."
        }

        findViewById<Button>(R.id.startButton).setOnClickListener { requestVpnAndStart() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            val stopIntent = Intent(this, SimpleFirewallService::class.java).apply {
                action = SimpleFirewallService.ACTION_STOP
            }
            startService(stopIntent)
            findViewById<TextView>(R.id.statusLabel).text = "Firewall stopped."
        }
    }

    private fun requestVpnAndStart() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            startService(Intent(this, SimpleFirewallService::class.java))
            findViewById<TextView>(R.id.statusLabel).text = "Firewall running."
        }
    }
}
