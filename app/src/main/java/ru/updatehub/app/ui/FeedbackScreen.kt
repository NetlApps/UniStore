package ru.updatehub.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.updatehub.app.data.DiagnosticsLog
import ru.updatehub.app.data.FeedbackCategory
import ru.updatehub.app.ui.components.AnimatedGlowBackground
import ru.updatehub.app.ui.components.ScreenHeader
import ru.updatehub.app.ui.components.SectionCard

/**
 * A normal bug-report form with a ready diagnostics block. The user can edit
 * the description, copy the whole report or open a prefilled GitHub issue.
 * Nothing is uploaded automatically: the report leaves the phone only after
 * the user taps a button and confirms the destination app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    buildReport: (FeedbackCategory, String) -> String
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(FeedbackCategory.BUG) }
    var description by remember { mutableStateOf("") }
    var showPreview by remember { mutableStateOf(false) }
    var crashReport by remember { mutableStateOf(DiagnosticsLog.readCrashReport(context)) }

    val report = remember(category, description, crashReport) {
        buildReport(category, description)
    }

    fun copyReport(notify: Boolean = true) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UniStore report", report))
        if (notify) onMessage("Отчёт скопирован")
    }

    fun shareReport() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "UniStore: ${category.title}")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Отправить отчёт"))
        }.onFailure { onMessage("Нет приложения для отправки") }
    }

    fun openGithubIssue() {
        // A URL cannot carry the whole diagnostic report: GitHub limits the
        // request line and Cyrillic expands sixfold after encoding. Copy the
        // full text first so the user can paste it into the issue body.
        copyReport(notify = false)
        val url = DiagnosticsLog.githubIssueUrl(category, report)
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        onMessage(
            if (opened) {
                "Полный отчёт скопирован — вставьте его в описание на GitHub"
            } else {
                "Не удалось открыть GitHub. Полный отчёт скопирован"
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedGlowBackground(intensity = 0.8f)

        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Обратная связь",
                subtitle = "Ошибка и диагностика в одном отчёте",
                onBack = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(contentPadding = PaddingValues(18.dp)) {
                    Text(
                        text = "Категория",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Отметьте, что случилось — от этого зависит метка на GitHub.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FeedbackCategory.entries.forEach { option ->
                            FilterChip(
                                selected = category == option,
                                onClick = { category = option },
                                label = { Text(option.title) }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(4_000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Что произошло") },
                        placeholder = {
                            Text("Например: после проверки обновлений приложение закрылось. " +
                                "Перед этим я открыл карточку WhatsApp.")
                        },
                        minLines = 4,
                        maxLines = 8,
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Чем точнее описание, тем быстрее получится найти причину. " +
                            "Описание можно оставить пустым — диагностика уже записана.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(contentPadding = PaddingValues(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Диагностика",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (crashReport != null) {
                                    "Найдена запись о последнем сбое"
                                } else {
                                    "Последние действия и версия приложения"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showPreview = !showPreview }) {
                            Text(if (showPreview) "Скрыть" else "Показать")
                        }
                    }

                    if (crashReport != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Запись о сбое попадёт в отчёт.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = {
                                    DiagnosticsLog.clearCrashReport(context)
                                    crashReport = null
                                    onMessage("Запись о сбое удалена")
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Удалить")
                            }
                        }
                    }

                    if (showPreview) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            color = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Text(
                                text = report,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = { openGithubIssue() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть на GitHub")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { copyReport() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Скопировать")
                    }
                    OutlinedButton(
                        onClick = { shareReport() },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Поделиться")
                    }
                }

                Text(
                    text = "Отчёт не отправляется автоматически. Кнопка «Открыть на GitHub» " +
                        "откроет форму с сокращённым текстом, а полная версия будет " +
                        "скопирована в буфер обмена для вставки.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
