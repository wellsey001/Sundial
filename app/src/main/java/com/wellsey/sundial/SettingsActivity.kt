package com.wellsey.sundial

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleWindow()
        prefs = Prefs(this)
        val root = vbox().apply { setBackgroundColor(Pal.bg) }
        val bar = hbox().apply { setPadding(dp(8), dp(10), dp(14), dp(10)) }
        bar.addView(iconButton(Icon.Kind.BACK) { finish() })
        bar.addView(label("Settings", 20f, Pal.text, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
        root.addView(bar, lp(MATCH, WRAP))
        val scroll = ScrollView(this)
        body = vbox().apply { setPadding(dp(14), 0, dp(14), dp(32)) }
        scroll.addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        setContentView(root)
        build()
    }

    private fun build() {
        body.removeAllViews()
        val cals = Cal.calendars(this)

        section("General")
        val general = vbox()
        general.addView(item("Week starts on", prefs.weekStart.getDisplayName(TextStyle.FULL, Locale.getDefault())) {
            val days = listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY)
            AlertDialog.Builder(this).setTitle("Week starts on")
                .setSingleChoiceItems(days.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }.toTypedArray(),
                    days.indexOf(prefs.weekStart)) { dlg, which ->
                    prefs.weekStart = days[which]; dlg.dismiss(); build()
                }.show()
        })
        val writable = cals.filter { it.writable }
        val def = writable.firstOrNull { it.id == prefs.defaultCalendar }
        general.addView(item("New events go in", def?.name ?: "Your main calendar") {
            if (writable.isEmpty()) return@item
            AlertDialog.Builder(this).setTitle("New events go in")
                .setSingleChoiceItems(writable.map { "${it.name}  ·  ${it.account}" }.toTypedArray(),
                    writable.indexOfFirst { it.id == prefs.defaultCalendar }) { dlg, which ->
                    prefs.defaultCalendar = writable[which].id; dlg.dismiss(); build()
                }.show()
        })
        val reminderOpts = listOf(-1, 0, 5, 10, 15, 30, 60)
        val reminderNames = listOf("None", "At the time", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 hour")
        val ri = reminderOpts.indexOf(prefs.defaultReminder).coerceAtLeast(0)
        general.addView(item("Default reminder", reminderNames[ri]) {
            AlertDialog.Builder(this).setTitle("Default reminder")
                .setSingleChoiceItems(reminderNames.toTypedArray(), ri) { dlg, which ->
                    prefs.defaultReminder = reminderOpts[which]; dlg.dismiss(); build()
                }.show()
        })
        general.addView(toggle("Reminder notifications",
            "If you also get reminders from another calendar app, turn its notifications off", prefs.notify) { on ->
            prefs.notify = on
            if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        general.addView(item("Sync now", "Ask your accounts to fetch the latest events") {
            Cal.syncNow(this)
            Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
        })
        body.addView(card(general))

        section("Calendars")
        val hidden = prefs.hidden.toMutableSet()
        for ((account, list) in cals.groupBy { it.account }) {
            val box = vbox()
            val accountName = if (list.first().accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) "On this phone" else account
            box.addView(label(accountName, 13f, Pal.sub, bold = true).apply { setPadding(dp(16), dp(10), dp(16), dp(4)) })
            for (cal in list) {
                val row = hbox().apply { setPadding(dp(16), dp(8), dp(12), dp(8)); minimumHeight = dp(50) }
                row.addView(View(this).apply { background = rounded(cal.color, dpf(7f)) },
                    lp(dp(14), dp(14)).apply { rightMargin = dp(14) })
                val names = vbox()
                names.addView(label(cal.name, 16f))
                if (!cal.writable) names.addView(label("Read-only", 12f, Pal.sub).apply { setPadding(0, dp(2), 0, 0) })
                row.addView(names, lp(0, WRAP, 1f))
                val sw = Switch(this).apply {
                    isChecked = cal.id !in hidden
                    setOnCheckedChangeListener { _, on ->
                        if (on) hidden.remove(cal.id) else hidden.add(cal.id)
                        prefs.hidden = hidden.toSet()
                        AgendaWidget.refresh(this@SettingsActivity)
                    }
                }
                row.addView(sw)
                row.pressable(dpf(14f))
                row.setOnClickListener { sw.toggle() }
                box.addView(row)
            }
            body.addView(card(box))
        }
        if (cals.isEmpty()) {
            body.addView(label("No calendars found. Add a Google or other account in your phone's settings, or make one below.",
                14f, Pal.sub, lines = 0).apply { setPadding(dp(8), 0, dp(8), dp(12)) })
        }
        if (cals.none { it.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL }) {
            body.addView(card(item("Add a calendar on this phone", "Stays on this phone and isn't synced") {
                if (Cal.createLocalCalendar(this) >= 0) build()
                else Toast.makeText(this, "Couldn't make the calendar", Toast.LENGTH_SHORT).show()
            }))
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        body.addView(label("Sundial $version", 12f, Pal.faint).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(20), 0, 0)
        }, lp(MATCH, WRAP))
    }

    private fun section(name: String) {
        body.addView(label(name.uppercase(), 12f, Pal.sub, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(8), dp(18), 0, dp(8))
        })
    }

    private fun card(content: View): View = FrameLayout(this).apply {
        background = rounded(Pal.surface, dpf(20f))
        setPadding(0, dp(4), 0, dp(4))
        clipToOutline = true
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
        layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(8) }
    }

    private fun toggle(title: String, value: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = hbox().apply { setPadding(dp(16), dp(12), dp(12), dp(12)) }
        val texts = vbox()
        texts.addView(label(title, 16f))
        texts.addView(label(value, 13f, Pal.sub, lines = 3).apply { setPadding(0, dp(3), dp(8), 0) })
        row.addView(texts, lp(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        row.pressable(dpf(14f))
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun item(title: String, value: String, onClick: () -> Unit): View {
        val row = vbox().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            pressable(dpf(14f))
            setOnClickListener { onClick() }
        }
        row.addView(label(title, 16f))
        row.addView(label(value, 13f, Pal.sub, lines = 2).apply { setPadding(0, dp(3), 0, 0) })
        return row
    }
}
