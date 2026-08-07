package com.example.selflearn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Simple, lightweight file browser using ListView (not RecyclerView) to keep memory low.
 * Starts at external storage root and lets you navigate down / open files with their
 * default app, or back up a level.
 */
class FileBrowserActivity : AppCompatActivity() {

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private lateinit var pathLabel: TextView
    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        pathLabel = findViewById(R.id.pathLabel)
        listView = findViewById(R.id.fileListView)

        listDirectory(currentDir)

        listView.setOnItemClickListener { _, _, position, _ ->
            val entries = currentDir.listFiles()?.sortedBy { it.name } ?: return@setOnItemClickListener
            val adjustedPosition = if (currentDir.parentFile != null) position - 1 else position

            if (currentDir.parentFile != null && position == 0) {
                // ".. (up one level)" was tapped
                listDirectory(currentDir.parentFile!!)
                return@setOnItemClickListener
            }

            val chosen = entries.getOrNull(adjustedPosition) ?: return@setOnItemClickListener
            if (chosen.isDirectory) {
                listDirectory(chosen)
            } else {
                openFile(chosen)
            }
        }
    }

    private fun listDirectory(dir: File) {
        currentDir = dir
        pathLabel.text = dir.absolutePath
        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        val labels = mutableListOf<String>()
        if (dir.parentFile != null) labels.add(".. (up one level)")
        for (f in entries) labels.add(if (f.isDirectory) "📁 ${f.name}" else "📄 ${f.name}")
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun openFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file.", Toast.LENGTH_SHORT).show()
        }
    }
}
