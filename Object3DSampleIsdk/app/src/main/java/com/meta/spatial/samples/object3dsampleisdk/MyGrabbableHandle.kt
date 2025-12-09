package com.meta.spatial.samples.object3dsampleisdk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.meta.spatial.toolkit.PanelConstants.DEFAULT_DP_PER_METER

@Composable
@Preview(
    widthDp = (DEFAULT_DP_PER_METER * PANEL_WIDTH).toInt(),
    heightDp = (DEFAULT_DP_PER_METER * PANEL_HEIGHT).toInt(),
)
fun GrabbablePanelPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(modifier = Modifier
            .fillMaxSize(),

        onClick = { })
        {
            Text(
                text ="click",
            )
        }
    }
}