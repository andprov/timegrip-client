package ru.timegrip.app.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import ru.timegrip.app.R
import ru.timegrip.app.ui.account.AccountScreen
import ru.timegrip.app.ui.dashboard.DashboardScreen
import ru.timegrip.app.ui.projects.ProjectsScreen
import ru.timegrip.app.ui.report.ReportScreen
import ru.timegrip.app.ui.timers.TimersScreen

private data class Tab(
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val tabs = listOf(
    Tab(R.string.nav_dashboard, Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    Tab(R.string.nav_projects, Icons.Outlined.Folder, Icons.Filled.Folder),
    Tab(R.string.nav_timers, Icons.Outlined.Timer, Icons.Filled.Timer),
    Tab(R.string.nav_report, Icons.AutoMirrored.Outlined.Article, Icons.AutoMirrored.Filled.Article),
    Tab(R.string.nav_account, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

/** Messages any screen can show above the bottom bar. */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/**
 * The signed-in app (web: components/layout/AppLayout.tsx): the header's
 * page links become a bottom navigation bar and the running-timer controls
 * sit right above it, reachable from every tab.
 */
@Composable
fun MainScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // Back from any other tab returns to the first one, as it did when the tabs were a back
    // stack; on the first tab the system handles it and the app goes to the background.
    BackHandler(enabled = pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    RunningTimerBar()
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            val selected = pagerState.currentPage == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            // The tabs are the pages of a pager, so a screen follows the finger and the next one
            // comes in behind it. The bar below is another way of turning to a page, and the
            // neighbouring page is composed in advance so it is already drawn when it appears.
            HorizontalPager(
                state = pagerState,
                // No screen has a bar of its own any more, so the pages keep clear of the
                // status bar here, once, instead of each screen doing it for itself.
                modifier = Modifier
                    .padding(padding)
                    .statusBarsPadding(),
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                when (page) {
                    0 -> DashboardScreen()
                    1 -> ProjectsScreen()
                    2 -> TimersScreen()
                    3 -> ReportScreen()
                    else -> AccountScreen()
                }
            }
        }
    }
}
