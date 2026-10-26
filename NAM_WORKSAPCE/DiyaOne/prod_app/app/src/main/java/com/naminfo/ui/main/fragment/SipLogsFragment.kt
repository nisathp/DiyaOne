package com.naminfo.ui.main.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.naminfo.R
import com.naminfo.core.SipTraceRepository
import com.naminfo.databinding.SipLogsFragmentBinding
import java.util.Date

class SipLogsFragment : GenericMainFragment() {
    private lateinit var binding: SipLogsFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SipLogsFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.lifecycleOwner = viewLifecycleOwner

        binding.setBackClickListener { goBack() }
        binding.setCopyClickListener { copyTrace() }
        binding.setClearClickListener { confirmClear() }

        SipTraceRepository.entries.observe(viewLifecycleOwner, ::showEntries)
    }

    private fun showEntries(entries: List<SipTraceRepository.Entry>) {
        binding.messages.removeAllViews()
        binding.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.copy.isEnabled = entries.isNotEmpty()
        binding.clear.isEnabled = entries.isNotEmpty()

        entries.groupBy { entry ->
            entry.callId.takeIf { it.isNotBlank() } ?: "unknown-${entry.timestamp}"
        }.values.forEachIndexed { index, callEntries ->
            binding.messages.addView(createCallSectionHeader(index + 1))
            callEntries.forEach { entry ->
                binding.messages.addView(createEntryCard(entry))
            }
        }
    }

    private fun createCallSectionHeader(callNumber: Int): View {
        val context = requireContext()
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = context.dp(8)
                bottomMargin = context.dp(10)
            }
        }
        section.addView(
            TextView(context).apply {
                text = context.getString(R.string.sip_logs_call_title, callNumber)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.themeColor(R.attr.color_text))
                setPadding(0, 0, 0, context.dp(8))
            }
        )
        section.addView(
            View(context).apply {
                setBackgroundColor(context.themeColor(R.attr.color_separator))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dp(1)
                )
            }
        )
        return section
    }

    private fun createEntryCard(entry: SipTraceRepository.Entry): View {
        val context = requireContext()
        val card = MaterialCardView(context).apply {
            radius = context.dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = context.dp(1)
            setStrokeColor(context.themeColor(com.naminfo.R.attr.color_separator))
            setCardBackgroundColor(context.themeColor(com.naminfo.R.attr.color_background_contrast_in_dark_mode))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(10) }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        }
        card.addView(content)

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val directionLabel = TextView(context).apply {
            text = when (entry.direction) {
                SipTraceRepository.Direction.SENT -> context.getString(R.string.sip_logs_sent)
                SipTraceRepository.Direction.RECEIVED -> context.getString(R.string.sip_logs_received)
                SipTraceRepository.Direction.UNKNOWN -> context.getString(R.string.sip_logs_direction_unknown)
            }
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.getColor(R.color.bc_white))
            val color = when (entry.direction) {
                SipTraceRepository.Direction.SENT -> context.getColor(R.color.success_500)
                SipTraceRepository.Direction.RECEIVED -> context.getColor(R.color.info_500)
                SipTraceRepository.Direction.UNKNOWN -> context.getColor(R.color.main2_500)
            }
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = context.dp(10).toFloat()
            }
            setPadding(context.dp(9), context.dp(5), context.dp(9), context.dp(5))
        }
        heading.addView(directionLabel)

        val time = TextView(context).apply {
            text = DateFormat.format("HH:mm:ss", Date(entry.timestamp))
            textSize = 12f
            setTextColor(context.themeColor(com.naminfo.R.attr.color_main2_600))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        heading.addView(time, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(heading)

        val protocolTitle = TextView(context).apply {
            text = entry.label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.themeColor(com.naminfo.R.attr.color_text))
            setPadding(0, context.dp(9), 0, 0)
        }
        content.addView(protocolTitle)

        val from = entry.headerValue("From")
        val to = entry.headerValue("To")
        if (!from.isNullOrBlank() || !to.isNullOrBlank()) {
            content.addView(
                TextView(context).apply {
                text = buildString {
                    if (!from.isNullOrBlank()) {
                        append(context.getString(R.string.sip_logs_from, from))
                    }
                    if (!to.isNullOrBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(context.getString(R.string.sip_logs_to, to))
                    }
                }
                textSize = 12f
                setTextColor(context.themeColor(com.naminfo.R.attr.color_main2_600))
                setPadding(0, context.dp(4), 0, 0)
            }
            )
        }

        content.addView(
            TextView(context).apply {
            text = entry.mediaSummary
            textSize = 12f
            setTextColor(context.themeColor(com.naminfo.R.attr.color_text))
            setPadding(0, context.dp(4), 0, 0)
        }
        )

        val details = TextView(context).apply {
            text = entry.details
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(context.themeColor(com.naminfo.R.attr.color_text))
            setTextIsSelectable(true)
            setPadding(context.dp(10), context.dp(9), context.dp(10), context.dp(9))
            background = GradientDrawable().apply {
                setColor(context.themeColor(com.naminfo.R.attr.color_grey_100))
                cornerRadius = context.dp(8).toFloat()
            }
            visibility = View.GONE
        }
        val detailToggle = TextView(context).apply {
            text = context.getString(R.string.sip_logs_details)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(context.themeColor(com.naminfo.R.attr.color_main1_500))
            setPadding(0, context.dp(10), 0, 0)
            isClickable = true
            setOnClickListener {
                val expanded = details.visibility != View.VISIBLE
                details.visibility = if (expanded) View.VISIBLE else View.GONE
                text = context.getString(
                    if (expanded) R.string.sip_logs_hide_details else R.string.sip_logs_details
                )
            }
        }
        content.addView(detailToggle)
        content.addView(details)
        return card
    }

    private fun SipTraceRepository.Entry.headerValue(name: String): String? = details
        .lineSequence()
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

    private fun copyTrace() {
        val trace = SipTraceRepository.copyableTrace()
        if (trace.isBlank()) return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SIP trace", trace))
        (requireActivity() as? com.naminfo.ui.GenericActivity)?.showGreenToast(
            getString(R.string.sip_logs_copied),
            R.drawable.info
        )
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sip_logs_clear_confirm_title)
            .setMessage(R.string.sip_logs_clear_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.sip_logs_clear) { _, _ ->
                SipTraceRepository.clear()
                (requireActivity() as? com.naminfo.ui.GenericActivity)?.showGreenToast(
                    getString(R.string.sip_logs_cleared),
                    R.drawable.info
                )
            }
            .show()
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun Context.themeColor(attribute: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attribute, value, true)) return ContextCompat.getColor(this, R.color.main2_600)
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }
}
