package io.github.k1midi0.infinitesimal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.LogLine
import io.github.k1midi0.infinitesimal.spatial.cell
import io.github.k1midi0.infinitesimal.world.Entity
import kotlin.math.roundToInt

/**
 * 最小界面：**地图 + 东西列表 + 观察窗口 + 日志 + 两个输入框**。
 *
 * 它存在的理由不是"好看"，而是**把已经做好的部件第一次装到一起**，看一整轮到底几次调用、玩家要等
 * 多久。所以这里刻意不做：动画、布局自适应、设置页、存档。
 *
 * 三处刻意的设计：
 *
 * - **日志把系统发生的事都说出来**（哪个对象对齐过、哪条描述作废、固化有没有命中）。这一版是给
 *   开发者看的；给玩家的那一版该只留下 [LogLine.Kind.SAY]/[LogLine.Kind.OK]/[LogLine.Kind.REJECT]。
 * - **地图上点一下只改"在看哪个"，不改勾选**；列表上点一下才切换勾选。浏览地图不该打乱你选好的东西。
 * - **"看它一眼"是个按钮，不是自动的**。被判定改过的东西描述会作废，但**不会自动重渲染**——
 *   劈一次桌子若自动重渲染七个子物体，其中六个玩家根本不会去看（§7.1）。
 */
@Composable
fun GameScreen(session: GameSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    var acted by remember { mutableStateOf("") }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            TopBar(session)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapPane(session, Modifier.weight(1f).fillMaxHeight())
                ThingsPane(session, Modifier.weight(1.15f).fillMaxHeight())
                LogPane(session, Modifier.weight(1.2f).fillMaxHeight())
            }
            Spacer(Modifier.height(8.dp))
            // §4.7 的纠正预览。**它只在第一步说"你要动的不是这些"时出现**——那时世界还一个字都
            // 没动，所以关掉它是免费的。
            session.pending?.let { p ->
                PendingPanel(
                    session = session,
                    onApply = { session.applyPending(scope) },
                    onRewrite = {
                        acted = p.intention
                        session.dismissPending()
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            InputBar(
                said = said,
                acted = acted,
                busy = session.busy,
                onSaid = { said = it },
                onActed = { acted = it },
                onSubmit = {
                    session.submit(scope, said, acted)
                    said = ""
                    acted = ""
                },
            )
        }
    }
}

// ── 顶栏 ────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(session: GameSession) {
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("无限小", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Text(
            "${session.clock}｜世界里 ${session.entities.size} 样东西｜模型调用 ${session.requestCount} 次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        /*
         * 「等到下一件事」（§8.1）。
         *
         * **没有可等的事时按钮是禁的，而不是按下去往前推一小时。** 时间轴上没有将来，说明的是"没人
         * 安排过接下来会发生什么"，不是一个可以被绕过的障碍——凭空推进去会造出一段谁都没看见的时间，
         * 而那种空白在日志里和"真的无事发生"长得一模一样。
         */
        val next = session.nextStop
        TextButton(
            onClick = { session.waitUntilNext(scope) },
            enabled = !session.busy && session.pending == null && next != null,
        ) {
            Text(if (next == null) "没有可等的" else "等到 ${next.short}")
        }
        Spacer(Modifier.weight(1f))
        if (session.selection.isNotEmpty()) {
            Text(
                "勾选中 ${session.selection.size} 样：${session.selection.joinToString("、") { session.world.byId(it)?.label ?: it }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = { session.clearSelection() }) { Text("清空") }
        }
        if (session.busy) {
            Text(
                session.status.ifBlank { "跑着…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

// ── 地图 ────────────────────────────────────────────────────────────────────────────

/**
 * 俯视图。**画的是"这一层地面的平面图"**：
 *
 * - 大点 = 站在地上的东西（米格层 0）
 * - 小点 = 摆在它们上面的东西（米格层 > 0，且米格与某个地面东西重合）
 *
 * **一切都靠坐标推。** 以前这里读的是 `parent`（玩家挂在哪个地点、酒杯挂在哪张桌子上），那条字段
 * 已经删掉了——关系不存成事实，位置就是坐标（附录 A.5）。所以"这东西摆在什么上面"现在的判据是
 * **它的米格压在地面某个东西的米格上**。等东西有了形状，这条判据会换成"它的底面贴着那东西的顶面"
 * （A.10），但方向一样：从几何推，不从字段读。
 *
 * 房间的名字也不画了：那以前来自 `parent`，而"这一片属于哪个地点"要等地点的范围（形状）才有答案。
 */
@Composable
private fun MapPane(session: GameSession, modifier: Modifier) {
    val entities = session.entities
    val ground = entities.filter { it.cell().z == 0 }
    val satellites = entities.filter { e ->
        e.cell().z != 0 && ground.any { g -> g.cell().x == e.cell().x && g.cell().y == e.cell().y }
    }

    Pane("俯视图", modifier) {
        if (ground.isEmpty()) {
            Text("这一层地上没有东西。", style = MaterialTheme.typography.bodySmall)
            return@Pane
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            val pad = 54f
            val minX = ground.minOf { it.cell().x }
            val minY = ground.minOf { it.cell().y }
            val spanX = (ground.maxOf { it.cell().x } - minX).coerceAtLeast(0)
            val spanY = (ground.maxOf { it.cell().y } - minY).coerceAtLeast(0)
            val cell = minOf(
                (w - pad * 2) / (spanX + 1).toFloat(),
                (h - pad * 2) / (spanY + 1).toFloat(),
                110f,
            ).coerceAtLeast(30f)

            fun at(x: Int, y: Int) = Offset(
                pad + (x - minX + 0.5f) * cell,
                pad + (y - minY + 0.5f) * cell,
            )

            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val groundColor = MaterialTheme.colorScheme.primary
            val satelliteColor = MaterialTheme.colorScheme.tertiary
            val picked = session.selection
            val focusedId = session.focused
            // 判定纠正后要标出来的那些（§4.7）。**地图上会发光**——玩家一眼看到"它以为我要动的是
            // 那个"，而这一步世界还没动。
            val pendingTargets = session.pending?.targets?.toSet().orEmpty()
            val pendingGlow = MaterialTheme.colorScheme.tertiary

            Canvas(Modifier.fillMaxSize()) {
                for (i in 0..spanX) {
                    val x = at(minX + i, minY).x
                    drawLine(gridColor, Offset(x, pad * 0.5f), Offset(x, size.height - pad * 0.5f), 1f)
                }
                for (j in 0..spanY) {
                    val y = at(minX, minY + j).y
                    drawLine(gridColor, Offset(pad * 0.5f, y), Offset(size.width - pad * 0.5f, y), 1f)
                }
                ground.forEach { e ->
                    val p = at(e.cell().x, e.cell().y)
                    if (e.id in pendingTargets) {
                        drawCircle(pendingGlow.copy(alpha = 0.20f), 22f, p)
                        drawCircle(pendingGlow, 14f, p, alpha = 0.9f, style = Stroke(width = 2.5f))
                    }
                    if (e.id == focusedId) drawCircle(groundColor.copy(alpha = 0.22f), 17f, p)
                    drawCircle(if (e.id in picked) groundColor else groundColor.copy(alpha = 0.45f), 9f, p)
                }
                // 摆在上面那些沿着它下面的地面东西排开——不然它们和桌子是同一个米格，会叠在一起。
                satellites.groupBy { it.cell().x to it.cell().y }.forEach { (atCell, list) ->
                    val base = at(atCell.first, atCell.second)
                    list.forEachIndexed { index, e ->
                        val angle = (-1.4f + index * 0.62f).toDouble()
                        val p = base + Offset(
                            (kotlin.math.cos(angle) * cell * 0.42).toFloat(),
                            (kotlin.math.sin(angle) * cell * 0.42).toFloat(),
                        )
                        if (e.id in pendingTargets) {
                            drawCircle(pendingGlow.copy(alpha = 0.20f), 17f, p)
                            drawCircle(pendingGlow, 10f, p, alpha = 0.9f, style = Stroke(width = 2f))
                        }
                        if (e.id == focusedId) drawCircle(satelliteColor.copy(alpha = 0.22f), 13f, p)
                        drawCircle(if (e.id in picked) satelliteColor else satelliteColor.copy(alpha = 0.5f), 6f, p)
                    }
                }
            }

            ground.forEach { e ->
                MapLabel(session, e, at(e.cell().x, e.cell().y), cell)
            }
        }
        Text(
            "大点是地面上的，小点是摆在它们上面的。点一下只是「看它」，不动勾选。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MapLabel(session: GameSession, entity: Entity, pos: Offset, cell: Float) {
    val density = LocalDensity.current
    val widthPx = (cell * 1.8f).coerceAtLeast(56f)
    Box(
        Modifier
            .offset {
                IntOffset(
                    (pos.x - widthPx / 2f).roundToInt(),
                    (pos.y + 12f).roundToInt(),
                )
            }
            .width(with(density) { widthPx.toDp() })
            .clickable { session.focus(entity.id) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            entity.label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = if (entity.id == session.focused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (entity.id == session.focused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

// ── 东西列表 + 观察窗口 ─────────────────────────────────────────────────────────────

@Composable
private fun ThingsPane(session: GameSession, modifier: Modifier) {
    Pane("东西（点一下勾选它）", modifier) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(session.entities, key = { it.id }) { entity ->
                    ThingRow(session, entity)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        ObservationWindow(session)
    }
}

@Composable
private fun ThingRow(session: GameSession, entity: Entity) {
    val picked = entity.id in session.selection
    val focused = entity.id == session.focused
    val seen = entity.id in session.observations
    Card(
        Modifier.fillMaxWidth().clickable { session.click(entity.id) },
        colors = CardDefaults.cardColors(
            containerColor = when {
                focused -> MaterialTheme.colorScheme.primaryContainer
                picked -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (picked) "☑" else "☐", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(entity.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    session.subtitleOf(entity),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 未观察的东西在界面上必须看得出来——"还没看过"和"看过了没什么可说的"是两回事。
            Text(
                if (seen) "已看过" else "没见过",
                style = MaterialTheme.typography.bodySmall,
                color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun ObservationWindow(session: GameSession) {
    val scope = rememberCoroutineScope()
    val entity = session.focusedEntity
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entity?.let { "看着：${it.label}" } ?: "没在看任何东西",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { session.observeFocused(scope) },
                enabled = entity != null && !session.busy,
            ) { Text("看它一眼") }
        }
        Spacer(Modifier.height(6.dp))
        when {
            entity == null -> Text("在左边点一样东西。", style = MaterialTheme.typography.bodySmall)

            session.focusedObservation == null -> {
                Text(
                    if (entity.sentences.isEmpty()) "还没看过它——它的样子现在谁都不知道。"
                    else "它已经固化过 ${entity.sentences.size} 句，但这一版还没取回来（点一下上面的按钮）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            else -> {
                val obs = session.focusedObservation!!
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    obs.sentences.forEachIndexed { index, sentence ->
                        Text(
                            "${index + 1}. $sentence",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (obs.fromCache) "固化命中（0 请求）" else "${obs.elapsedMs} ms")
                        obs.usage?.let { append("｜输入 ${it.promptTokens} 输出 ${it.completionTokens}") }
                        if (obs.violations.isNotEmpty()) append("｜抽查：${obs.violations.size} 条")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 日志 ────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogPane(session: GameSession, modifier: Modifier) {
    val state = rememberLazyListState()
    // 新的一行落下来就滚到底。不加这个，跑一轮之后玩家看到的是上一次的尾巴。
    LaunchedEffect(session.log.size) {
        if (session.log.isNotEmpty()) state.animateScrollToItem(session.log.size - 1)
    }
    Pane(
        title = "发生了什么",
        modifier = modifier,
        action = { TextButton(onClick = { session.clearLog() }) { Text("清空") } },
    ) {
        if (session.log.isEmpty()) {
            Text(
                "还没做什么。试着在下面写一句「我拿起桌上那杯麦酒」，什么都不用勾——\n" +
                    "判定的第一步会把「桌上那杯」对到具体的东西上。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(session.log) { line -> LogRow(line) }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.kind) {
        LogLine.Kind.SAY -> MaterialTheme.colorScheme.primary
        LogLine.Kind.ACT -> MaterialTheme.colorScheme.primary
        LogLine.Kind.OK -> Color(0xFF2E7D32)
        LogLine.Kind.REJECT -> MaterialTheme.colorScheme.error
        LogLine.Kind.PROBLEM -> MaterialTheme.colorScheme.error
        LogLine.Kind.ERROR -> MaterialTheme.colorScheme.error
        LogLine.Kind.NOTE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val prefix = when (line.kind) {
        LogLine.Kind.SAY -> "说"
        LogLine.Kind.ACT -> "做"
        LogLine.Kind.OK -> "成"
        LogLine.Kind.REJECT -> "驳"
        LogLine.Kind.PROBLEM -> "坏"
        LogLine.Kind.ERROR -> "错"
        LogLine.Kind.NOTE -> " "
    }
    Row(Modifier.fillMaxWidth()) {
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.width(16.dp),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 纠正预览（§4.7） ────────────────────────────────────────────────────────────────

/**
 * 「判定认为你要动的是这些，对吗？」
 *
 * 它出现的时候**世界一个字都还没动**——第一步只对齐、不改世界（§4.6），所以两个按钮都不需要
 * 回滚：应用就是接着跑第二步，改动作就是把这一次丢掉。
 */
@Composable
private fun PendingPanel(session: GameSession, onApply: () -> Unit, onRewrite: () -> Unit) {
    val p = session.pending ?: return
    val glow = MaterialTheme.colorScheme.tertiary
    Surface(
        color = glow.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    append("判定认为这次动作作用在：")
                    append(
                        if (p.targets.isEmpty()) "（不作用于具体的东西）"
                        else p.targets.joinToString("、") { session.world.byId(it)?.label ?: it },
                    )
                    append("　——和你勾的不一样。世界还没动。")
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onApply, enabled = !session.busy) { Text("应用") }
            // 「改动作」把原话填回输入框：多数时候玩家要改的只是勾选，话还是同一句。
            TextButton(onClick = onRewrite, enabled = !session.busy) { Text("改动作") }
        }
    }
}

// ── 底部输入 ────────────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    said: String,
    acted: String,
    busy: Boolean,
    onSaid: (String) -> Unit,
    onActed: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = said,
            onValueChange = onSaid,
            modifier = Modifier.weight(1f),
            label = { Text("说") },
            placeholder = { Text("「这酒怎么这么淡？」") },
            singleLine = true,
            enabled = !busy,
        )
        OutlinedTextField(
            value = acted,
            onValueChange = onActed,
            modifier = Modifier.weight(1f),
            label = { Text("做") },
            placeholder = { Text("我拿起桌上那杯麦酒") },
            singleLine = true,
            enabled = !busy,
        )
        Button(onClick = onSubmit, enabled = !busy && (said.isNotBlank() || acted.isNotBlank())) {
            Text(if (busy) "…" else "这一轮")
        }
    }
}

// ── 外壳 ────────────────────────────────────────────────────────────────────────────

@Composable
private fun Pane(
    title: String,
    modifier: Modifier,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                action?.invoke()
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
