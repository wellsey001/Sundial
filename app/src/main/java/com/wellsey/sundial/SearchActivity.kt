package com.wellsey.sundial

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import java.util.concurrent.Executors

/** Finds events by title, location or notes, from a year ago to two years ahead. */
class SearchActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var field: EditText
    private lateinit var results: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var token = 0
    private val run = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        styleWindow()
        prefs = Prefs(this)

        val root = vbox()
        val bar = hbox().apply { setPadding(dp(8), dp(10), dp(14), dp(10)) }
        bar.addView(iconButton(Icon.Kind.BACK) { finish() })
        val box = hbox().apply {
            glass(dpf(22f))
            setPadding(dp(14), 0, dp(10), 0)
        }
        box.addView(Icon(this, Icon.Kind.SEARCH, Pal.sub), lp(dp(20), dp(20)).apply { rightMargin = dp(10) })
        field = EditText(this).apply {
            hint = "Search events"
            textSize = 16f
            setTextColor(Pal.text)
            setHintTextColor(Pal.faint)
            background = null
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(0, dp(12), 0, dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    ui.removeCallbacks(run)
                    ui.postDelayed(run, 250)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                ui.removeCallbacks(run); search()
                getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
                true
            }
        }
        box.addView(field, lp(0, WRAP, 1f))
        box.addView(Icon(this, Icon.Kind.CLOSE, Pal.sub).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            pressable(dpf(14f))
            setOnClickListener { field.setText("") }
        }, lp(dp(28), dp(28)))
        bar.addView(box, lp(0, WRAP, 1f).apply { leftMargin = dp(4) })
        root.addView(bar, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        results = vbox().apply { setPadding(dp(10), dp(4), dp(10), dp(32)) }
        scroll.addView(results, FrameLayout.LayoutParams(MATCH, WRAP))
        root.addView(scroll, lp(MATCH, 0, 1f))
        setSkyContent(root)
        hint("Search titles, places and notes")

        field.requestFocus()
        field.postDelayed({
            getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    private fun hint(text: String) {
        results.removeAllViews()
        results.addView(label(text, 14f, Pal.sub, lines = 0).apply { setPadding(dp(12), dp(16), dp(12), 0) })
    }

    private fun search() {
        val q = field.text.toString().trim()
        val t = ++token
        if (q.isEmpty()) { hint("Search titles, places and notes"); return }
        val hidden = prefs.hidden
        io.execute {
            val all = Cal.search(this, q, hidden)
            // One line per event: the next time it happens, or the last time if it's in the past
            val now = System.currentTimeMillis()
            val best = all.groupBy { it.eventId }.values.map { occ ->
                occ.filter { it.end >= now }.minByOrNull { it.begin } ?: occ.maxBy { it.begin }
            }
            val upcoming = best.filter { it.end >= now }.sortedBy { it.begin }
            val past = best.filter { it.end < now }.sortedByDescending { it.begin }
            ui.post { if (t == token) show(q, upcoming, past) }
        }
    }

    private fun show(q: String, upcoming: List<Ev>, past: List<Ev>) {
        results.removeAllViews()
        if (upcoming.isEmpty() && past.isEmpty()) {
            hint("No events match \"$q\"")
            return
        }
        fun section(name: String, list: List<Ev>) {
            if (list.isEmpty()) return
            val card = vbox().apply {
                glass(dpf(24f))
                setPadding(dp(12), dp(12), dp(12), dp(4))
                layoutParams = lp(MATCH, WRAP).apply { bottomMargin = dp(10) }
            }
            card.addView(label(name, 14f, Pal.sub, bold = true).apply { setPadding(dp(4), dp(2), 0, dp(10)) })
            for (ev in list.take(100)) card.addView(eventRow(ev, null) { open(ev) })
            results.addView(card)
        }
        section("Coming up", upcoming)
        section("Past", past)
        results.staggerIn(this)
    }

    private fun open(ev: Ev) {
        startActivity(
            Intent(this, EventActivity::class.java)
                .putExtra("eventId", ev.eventId)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ev.end),
        )
    }
}
