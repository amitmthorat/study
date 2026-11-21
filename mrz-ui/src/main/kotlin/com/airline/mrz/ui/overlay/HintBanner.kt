package com.airline.mrz.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airline.mrz.ScanState
import com.airline.mrz.engine.Hint
import com.airline.mrz.ui.R
import com.airline.mrz.ui.theme.MrzTheme
import kotlinx.coroutines.delay

/** Maps the pipeline's hint to a localised string, debounced so the banner does not flicker. */
@Composable
public fun HintBanner(state: ScanState, theme: MrzTheme, modifier: Modifier = Modifier, debounceMs: Long = 400) {
    val target = hintTextRes(state)
    var shown by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        delay(debounceMs)
        shown = target
    }
    Text(
        text = stringResource(shown),
        color = theme.hintText,
        fontSize = 16.sp,
        modifier = modifier
            .background(theme.hintBackground, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

internal fun hintTextRes(state: ScanState): Int = when (state) {
    is ScanState.Idle, is ScanState.Searching -> R.string.mrz_hint_no_mrz
    is ScanState.Aligning -> when (state.hint) {
        Hint.NO_MRZ -> R.string.mrz_hint_no_mrz
        Hint.HOLD_STILL -> R.string.mrz_hint_hold_still
        Hint.AVOID_GLARE -> R.string.mrz_hint_avoid_glare
        Hint.MOVE_CLOSER -> R.string.mrz_hint_move_closer
        Hint.FIT_WHOLE_PASSPORT -> R.string.mrz_hint_fit_whole_passport
        Hint.HOLD_FLAT -> R.string.mrz_hint_hold_flat
        Hint.HOLD_PARALLEL -> R.string.mrz_hint_hold_parallel
        Hint.FLATTEN_PAGE -> R.string.mrz_hint_flatten_page
        Hint.MORE_LIGHT -> R.string.mrz_hint_more_light
    }
    is ScanState.Reading -> if (state.checksPassing) R.string.mrz_verifying else R.string.mrz_reading
    is ScanState.Done -> R.string.mrz_verifying
}
