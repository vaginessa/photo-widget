package com.fibelatti.photowidget.configure

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fibelatti.photowidget.R
import com.fibelatti.photowidget.model.PhotoWidgetLoopingInterval
import com.fibelatti.photowidget.platform.ComposeBottomSheetDialog
import com.fibelatti.ui.preview.DevicePreviews
import com.fibelatti.ui.preview.LocalePreviews
import com.fibelatti.ui.preview.ThemePreviews
import com.fibelatti.ui.theme.ExtendedTheme
import java.util.concurrent.TimeUnit

object PhotoWidgetIntervalPicker {

    fun show(
        context: Context,
        currentInterval: PhotoWidgetLoopingInterval,
        currentIntervalBasedLoopingEnabled: Boolean,
        onApplyClick: (newInterval: PhotoWidgetLoopingInterval, intervalBasedLoopingEnabled: Boolean) -> Unit,
    ) {
        ComposeBottomSheetDialog(context) {
            IntervalPickerContent(
                currentInterval = currentInterval,
                intervalBasedLoopingEnabled = currentIntervalBasedLoopingEnabled,
                onApplyClick = { newInterval, intervalBasedLoopingEnabled ->
                    onApplyClick(newInterval, intervalBasedLoopingEnabled)
                    dismiss()
                },
            )
        }.show()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun IntervalPickerContent(
    currentInterval: PhotoWidgetLoopingInterval,
    intervalBasedLoopingEnabled: Boolean,
    onApplyClick: (newInterval: PhotoWidgetLoopingInterval, intervalBasedLoopingEnabled: Boolean) -> Unit,
) {
    var interval by remember { mutableStateOf(currentInterval) }
    var enabled by remember(intervalBasedLoopingEnabled) { mutableStateOf(intervalBasedLoopingEnabled) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(all = 16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.photo_widget_configure_select_interval),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )

        AnimatedVisibility(
            visible = interval.toMinutes() < 5,
        ) {
            Text(
                text = stringResource(id = R.string.photo_widget_configure_interval_warning),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Slider(
                value = interval.repeatInterval.toFloat(),
                onValueChange = { newValue -> interval = interval.copy(repeatInterval = newValue.toLong()) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                valueRange = PhotoWidgetLoopingInterval.RANGE,
            )

            Text(
                text = "${interval.repeatInterval}",
                modifier = Modifier.width(40.dp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleLarge,
            )
        }

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val borderColor = SegmentedButtonDefaults.borderStroke(SegmentedButtonDefaults.colors().activeBorderColor)

            SegmentedButton(
                selected = TimeUnit.MINUTES == interval.timeUnit,
                onClick = { interval = interval.copy(timeUnit = TimeUnit.MINUTES) },
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                enabled = enabled,
                border = borderColor,
                label = {
                    Text(
                        text = stringResource(id = R.string.photo_widget_configure_interval_minutes_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )

            SegmentedButton(
                selected = TimeUnit.HOURS == interval.timeUnit,
                onClick = { interval = interval.copy(timeUnit = TimeUnit.HOURS) },
                shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
                enabled = enabled,
                border = borderColor,
                label = {
                    Text(
                        text = stringResource(id = R.string.photo_widget_configure_interval_hours_label),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
        }

        Row(
            modifier = Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
            )

            Text(
                text = stringResource(id = R.string.photo_widget_configure_interval_enabled),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        FilledTonalButton(
            onClick = { onApplyClick(interval, enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        ) {
            Text(
                text = stringResource(id = R.string.photo_widget_action_apply),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@ThemePreviews
@LocalePreviews
@DevicePreviews
private fun IntervalPickerContentPreview() {
    ExtendedTheme {
        IntervalPickerContent(
            currentInterval = PhotoWidgetLoopingInterval(
                repeatInterval = 1,
                timeUnit = TimeUnit.MINUTES,
            ),
            intervalBasedLoopingEnabled = true,
            onApplyClick = { _, _ -> },
        )
    }
}

@Composable
@ThemePreviews
@LocalePreviews
@DevicePreviews
private fun IntervalPickerContentDisabledPreview() {
    ExtendedTheme {
        IntervalPickerContent(
            currentInterval = PhotoWidgetLoopingInterval.ONE_DAY,
            intervalBasedLoopingEnabled = false,
            onApplyClick = { _, _ -> },
        )
    }
}
