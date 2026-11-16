package dev.asl.ui.settings

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.analytics.AnalyticsChartView

class AnalyticsFragment : Fragment(R.layout.settings_analytics) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val stats = view.findViewById<android.widget.TextView>(R.id.analytics_stats)
        val chart = view.findViewById<AnalyticsChartView>(R.id.analytics_chart)
        collectWhileStarted(Asl.analytics.data) { data ->
            val bootFailures = data.bootEvents.count { !it.success }
            val avgBootMs = if (data.bootEvents.isEmpty()) 0 else data.bootEvents.map { it.durationMs }.average()
            val mostUsed = data.distroUsage.maxByOrNull { it.value }?.key ?: "-"
            val avgTabs = if (data.tabSamples.isEmpty()) 0.0 else data.tabSamples.map { it.count }.average()
            val avgSessionMs = if (data.sessionDurationsMs.isEmpty()) 0.0 else data.sessionDurationsMs.average()
            stats.text = buildString {
                appendLine("Boot attempts: ${data.bootEvents.size}")
                appendLine("Boot failures: $bootFailures")
                appendLine("Average boot time: ${avgBootMs.toLong()} ms")
                appendLine("Most used distro: $mostUsed")
                appendLine("Average tabs: %.2f".format(avgTabs))
                appendLine("Sessions: ${data.sessionDurationsMs.size}")
                appendLine("Average session: ${avgSessionMs.toLong()} ms")
                appendLine("Resource samples: ${data.resourceSamples.size}")
            }
            chart.samples = data.resourceSamples
        }
    }
}