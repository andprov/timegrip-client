package ru.timegrip.app.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.timegrip.app.R

/** The dial logo; the hands follow the text color like `currentColor` on the web. */
@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(modifier.size(size)) {
        Image(painterResource(R.drawable.ic_logo_dial), contentDescription = null, modifier = Modifier.fillMaxSize())
        Icon(
            painterResource(R.drawable.ic_logo_hands),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
