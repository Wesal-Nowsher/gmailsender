package com.wykasolutions.gmailsender

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Decides which Gmail account sends to which recipient: all from one account,
 * split across several, or set one by one.
 */
class AssignActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var adapter: RowAdapter
    private lateinit var heading: TextView

    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#F6F8FC")

        if (!Batch.exists(this)) {
            Toast.makeText(this, "Build the queue first", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#F6F8FC"))
        }

        heading = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(heading)

        fun action(title: String, tint: String, run: () -> Unit) {
            Button(this).also {
                it.text = title
                it.isAllCaps = false
                it.textSize = 15f
                it.setTextColor(Color.WHITE)
                it.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
                root.addView(it, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
                it.setOnClickListener { run() }
            }
        }

        action("All from one account", "#D93025") { chooseOneForAll() }
        action("Split evenly across accounts", "#5F6368") { splitEvenly() }

        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(6)
        }
        adapter = RowAdapter()
        listView.adapter = adapter
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        setContentView(root)
        updateHeading()
    }

    private fun updateHeading() {
        val counts = Batch.counts(this)
        val accounts = Batch.perSender(this).size
        heading.text = "${counts.total} recipients across $accounts account" +
            (if (accounts == 1) "" else "s")
    }

    private fun chooseOneForAll() {
        val senders = Senders.list(this)
        if (senders.isEmpty()) {
            Toast.makeText(this, "Add a Gmail account first", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send everything from")
            .setItems(senders.toTypedArray()) { _, which ->
                Batch.assignAll(this, senders[which])
                adapter.notifyDataSetChanged()
                updateHeading()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun splitEvenly() {
        val senders = Senders.list(this)
        if (senders.size < 2) {
            Toast.makeText(this, "Add at least two accounts to split", Toast.LENGTH_LONG).show()
            return
        }
        val chosen = BooleanArray(senders.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Split across which accounts?")
            .setMultiChoiceItems(senders.toTypedArray(), chosen) { _, which, isChecked ->
                chosen[which] = isChecked
            }
            .setPositiveButton("Split") { _, _ ->
                val picked = senders.filterIndexed { i, _ -> chosen[i] }
                if (picked.isEmpty()) {
                    Toast.makeText(this, "Pick at least one account", Toast.LENGTH_LONG).show()
                } else {
                    Batch.splitAcross(this, picked)
                    adapter.notifyDataSetChanged()
                    updateHeading()
                    Toast.makeText(
                        this,
                        "Split across ${picked.size} accounts",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One row per recipient, with the account that will send to them. */
    private inner class RowAdapter : BaseAdapter() {

        override fun getCount() = Batch.size(this@AssignActivity)

        override fun getItem(position: Int): Any = position

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = convertView as? LinearLayout ?: LinearLayout(this@AssignActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.WHITE)
                addView(TextView(this@AssignActivity).apply {
                    textSize = 15f
                    setTextColor(Color.parseColor("#202124"))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Spinner(this@AssignActivity), LinearLayout.LayoutParams(dp(170), -2))
            }

            val emailView = row.getChildAt(0) as TextView
            val spinner = row.getChildAt(1) as Spinner

            val state = Batch.item(this@AssignActivity, position)?.optString("state").orEmpty()
            emailView.text = Batch.recipient(this@AssignActivity, position) +
                if (state == Batch.SENT) "  (sent)" else ""
            emailView.setTextColor(
                Color.parseColor(if (state == Batch.SENT) "#5F6368" else "#202124")
            )

            val senders = Senders.list(this@AssignActivity)
            val options = if (senders.isEmpty()) listOf("No accounts") else senders
            val spinnerAdapter = ArrayAdapter(
                this@AssignActivity,
                android.R.layout.simple_spinner_item,
                options
            )
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

            // Detach the listener before setting the value, so restoring the
            // saved choice is not mistaken for the owner changing it.
            spinner.onItemSelectedListener = null
            spinner.adapter = spinnerAdapter

            val current = Batch.sender(this@AssignActivity, position)
            val currentIndex = senders.indexOfFirst { it.equals(current, true) }
            if (currentIndex >= 0) spinner.setSelection(currentIndex)

            spinner.isEnabled = state != Batch.SENT && senders.isNotEmpty()
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val picked = senders.getOrNull(pos) ?: return
                    if (!picked.equals(Batch.sender(this@AssignActivity, position), true)) {
                        Batch.setSender(this@AssignActivity, position, picked)
                        updateHeading()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

            return row
        }
    }
}
