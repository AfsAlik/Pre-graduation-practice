package ru.finhelper.app.ui.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.finhelper.app.R
import ru.finhelper.app.ui.components.PrimaryButton

private class OnboardingPage(val icon: ImageVector, @StringRes val title: Int, @StringRes val text: Int)

private val PAGES = listOf(
    OnboardingPage(Icons.AutoMirrored.Filled.ReceiptLong, R.string.onboarding_title_1, R.string.onboarding_text_1),
    OnboardingPage(Icons.Filled.PieChart, R.string.onboarding_title_2, R.string.onboarding_text_2),
    OnboardingPage(Icons.Filled.Insights, R.string.onboarding_title_3, R.string.onboarding_text_3),
    OnboardingPage(Icons.Filled.CloudOff, R.string.onboarding_title_4, R.string.onboarding_text_4),
)

/**
 * Пошаговое знакомство нового пользователя с ключевыми функциями приложения
 * (онбординг, п. 4.2.1.8 в ТЗ). Показывается при первом запуске.
 *
 * @param initialPage номер страницы, открываемой первой
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit = {}, initialPage: Int = 0) {
    val pagerState = rememberPagerState(initialPage = initialPage) { PAGES.size }
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == PAGES.lastIndex
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFinish, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onboarding_skip)) }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { index ->
                val page = PAGES[index]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(144.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(page.title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(page.text),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
                PAGES.indices.forEach { index ->
                    val color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    Box(Modifier.padding(4.dp).size(10.dp).clip(CircleShape).background(color))
                }
            }
            Text(
                stringResource(R.string.onboarding_step, pagerState.currentPage + 1, PAGES.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            PrimaryButton(
                text = stringResource(if (lastPage) R.string.onboarding_start else R.string.onboarding_next),
                onClick = { if (lastPage) onFinish() else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            )
        }
    }
}
