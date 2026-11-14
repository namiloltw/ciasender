package com.nord.ciasender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val queue = mutableListOf<Item>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var ipField: EditText
    private lateinit var sendBtn: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            if (queue.none { it.uri == uri }) {
                val (name, size) = describe(uri)
                queue.add(Item(uri, name, size))
            }
        }
        refresh()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun describe(uri: Uri): Pair<String, Long> {
        var name = "archivo.cia"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n)
                if (s >= 0) size = c.getLong(s)
            }
        }
        return name to size
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        ipField = EditText(this).apply {
            hint = "IP de la 3DS (ej. 192.168.1.50)"
            setText(prefs.getString("ip", ""))
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        root.addView(ipField)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addBtn = Button(this).apply { text = "Añadir CIA" }
        val clearBtn = Button(this).apply { text = "Vaciar cola" }
        row.addView(addBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(clearBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        val hint = TextView(this).apply { text = "Toca un archivo de la cola para quitarlo" ; textSize = 12f }
        root.addView(hint)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply { this.adapter = this@MainActivity.adapter }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        sendBtn = Button(this).apply { text = "Enviar a la 3DS" }
        root.addView(sendBtn)

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        addBtn.setOnClickListener { picker.launch(arrayOf("*/*")) }
        clearBtn.setOnClickListener { queue.clear(); refresh() }
        list.setOnItemClickListener { _, _, pos, _ -> queue.removeAt(pos); refresh() }

        sendBtn.setOnClickListener {
            if (SendService.running) {
                startService(Intent(this, SendService::class.java).setAction("STOP"))
                return@setOnClickListener
            }
            val ip = ipField.text.toString().trim()
            if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
                Toast.makeText(this, "IP no válida", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (queue.isEmpty()) {
                Toast.makeText(this, "La cola está vacía", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            prefs.edit().putString("ip", ip).apply()
            SendService.items = queue.toList()
            val i = Intent(this, SendService::class.java).putExtra("ip", ip)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            updateUi()
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        SendService.listener = { updateUi() }
        updateUi()
    }

    override fun onStop() {
        SendService.listener = null
        super.onStop()
    }

    private fun refresh() {
        adapter.clear()
        adapter.addAll(queue.map { "${it.name}  (${if (it.size > 0) it.size / 1048576 else 0} MB)" })
        adapter.notifyDataSetChanged()
    }

    private fun updateUi() {
        sendBtn.text = if (SendService.running) "Parar" else "Enviar a la 3DS"
        synchronized(SendService.logText) { logView.text = SendService.logText.toString() }
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
