package com.naminfo.ui.main.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.naminfo.R
import com.naminfo.core.ApiLogRepository
import com.naminfo.databinding.ApiLogsFragmentBinding
import java.util.Date

class ApiLogsFragment : GenericMainFragment() {
    private lateinit var binding: ApiLogsFragmentBinding
    private var allEntries: List<ApiLogRepository.Entry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = ApiLogsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.setBackClickListener { goBack() }
        binding.setCopyClickListener { copyLogs() }
        binding.setClearClickListener { confirmClear() }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = showEntries(allEntries)

            override fun afterTextChanged(s: Editable?) = Unit
        })
        ApiLogRepository.entries.observe(viewLifecycleOwner) {
            allEntries = it
            showEntries(it)
        }
    }

    private fun showEntries(entries: List<ApiLogRepository.Entry>) {
        val query = binding.search.text?.toString()?.trim().orEmpty()
        val visible = if (query.isEmpty()) {
            entries
        } else {
            entries.filter {
            "${it.method} ${it.url} ${it.requestBody.orEmpty()} ${it.responseBody.orEmpty()} ${it.error.orEmpty()}"
                .contains(query, ignoreCase = true)
        }
        }
        binding.messages.removeAllViews()
        binding.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        binding.copy.isEnabled = entries.isNotEmpty()
        binding.clear.isEnabled = entries.isNotEmpty()
        visible.forEach { binding.messages.addView(createCard(it)) }
    }

    private fun createCard(entry: ApiLogRepository.Entry): View {
        val context = requireContext()
        return MaterialCardView(context).apply {
            radius = context.dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = context.dp(1)
            setStrokeColor(context.themeColor(R.attr.color_separator))
            setCardBackgroundColor(context.themeColor(R.attr.color_background_contrast_in_dark_mode))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = context.dp(10) }
            addView(
                LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
                addView(
                    TextView(context).apply {
                    text = entry.method
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(context.getColor(R.color.bc_white))
                    background = GradientDrawable().apply {
                        setColor(
                            context.getColor(
                                if (entry.error != null || (entry.statusCode ?: 200) >= 400) {
                                    R.color.red_danger_500
                                } else {
                                    R.color.green_success_500
                                },
                            ),
                        )
                        cornerRadius = context.dp(10).toFloat()
                    }
                    setPadding(context.dp(9), context.dp(5), context.dp(9), context.dp(5))
                }
                )
                addView(text(context, DateFormat.format("HH:mm:ss", Date(entry.timestamp)).toString(), 12f, R.attr.color_main2_600, 6))
                addView(text(context, entry.url, 13f, R.attr.color_text, 8, monospace = true))
                entry.requestBody?.takeIf {
                    it.isNotBlank()
                }?.let { addView(text(context, "Request\n$it", 12f, R.attr.color_text, 8, true)) }
                entry.statusCode?.let { addView(text(context, "Response HTTP $it", 12f, R.attr.color_text, 8)) }
                entry.responseBody?.takeIf {
                    it.isNotBlank()
                }?.let { addView(text(context, "Response\n$it", 12f, R.attr.color_text, 8, true)) }
                entry.error?.takeIf { it.isNotBlank() }?.let { addView(text(context, "Error\n$it", 12f, R.attr.color_text, 8)) }
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, context.dp(12), 0, 0)
                        addView(
                            actionButton(context, R.string.sip_logs_copy) {
                            copyEntry(entry)
                        }
                        )
                        addView(
                            actionButton(context, R.string.contact_details_share) {
                            shareEntry(entry)
                        }
                        )
                    }
                )
            }
            )
        }
    }

    private fun actionButton(context: Context, labelRes: Int, onClick: () -> Unit) =
        TextView(context).apply {
            text = context.getString(labelRes)
            textSize = 12f
            setTextColor(context.themeColor(R.attr.color_main1_500))
            background = ContextCompat.getDrawable(context, R.drawable.tertiary_button_background)
            setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
            setOnClickListener { onClick() }
        }

    private fun text(context: Context, value: String, size: Float, colorAttribute: Int, top: Int, monospace: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(context.themeColor(colorAttribute))
            if (monospace) typeface = Typeface.MONOSPACE
            setTextIsSelectable(monospace)
            setPadding(0, context.dp(top), 0, 0)
        }

    private fun copyLogs() {
        val logs = ApiLogRepository.copyableText()
        if (logs.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("API logs", logs))
        (requireActivity() as? com.naminfo.ui.GenericActivity)?.showGreenToast(getString(R.string.api_logs_copied), R.drawable.info)
    }

    private fun copyEntry(entry: ApiLogRepository.Entry) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("API log", entryText(entry)))
        (requireActivity() as? com.naminfo.ui.GenericActivity)?.showGreenToast(
            getString(R.string.api_logs_copied),
            R.drawable.info
        )
    }

    private fun shareEntry(entry: ApiLogRepository.Entry) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "API log")
            putExtra(Intent.EXTRA_TEXT, entryText(entry))
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.contact_details_share)))
    }

    private fun entryText(entry: ApiLogRepository.Entry): String = buildString {
        append("${entry.method} ${entry.url}")
        entry.requestBody?.takeIf { it.isNotBlank() }?.let { append("\nRequest: $it") }
        entry.statusCode?.let { append("\nResponse HTTP: $it") }
        entry.responseBody?.takeIf { it.isNotBlank() }?.let { append("\nResponse: $it") }
        entry.error?.takeIf { it.isNotBlank() }?.let { append("\nError: $it") }
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.api_logs_clear_title)
            .setMessage(R.string.api_logs_clear_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sip_logs_clear) { _, _ -> ApiLogRepository.clear() }
            .show()
    }

    private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun Context.themeColor(attribute: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) return ContextCompat.getColor(this, R.color.main2_600)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }
}
