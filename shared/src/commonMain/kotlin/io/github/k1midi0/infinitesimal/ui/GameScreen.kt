package io.github.k1midi0.infinitesimal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.k1midi0.infinitesimal.npc.Spoken
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.LogLine
import io.github.k1midi0.infinitesimal.spatial.cell
import io.github.k1midi0.infinitesimal.spatial.groundMetreCell
import io.github.k1midi0.infinitesimal.world.Entity
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 最小界面：**地图 + 东西列表 + 观察窗口 + 日志 + 两个输入框**。
 *
 * 它存在的理由不是"好看"，而是**把已经做好的部件第一次装到一起**，看一整轮到底几次调用、玩家要等
 * 多久。所以这里刻意不做：动画、布局自适应、设置页。
 *
 * **存档与读档在这里只有一个按钮**（§14.49）：读档在主界面那一边（那是一个新界面），而"存"要挑一个
 * 文件——挑文件是**平台的事**（桌面端是 `java.awt.FileDialog`），所以它由入口把那两个 lambda 递进来
 * （[onPickSavePath] / [onPickOpenPath]）。这一层只知道"给我一个路径"，不知道对话框长什么样；这也就
 * 是它不 import `java.awt` 的原因。
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
fun GameScreen(
    session: GameSession,
    modifier: Modifier = Modifier,
    /**
     * 挑一个"存到哪"的路径。返回 null = 玩家取消了。
     *
     * 默认是 null（不传）——那表示这一层没有挑文件的能力，于是那个按钮**不出现**而不是出现了却没反应。
     */
    onPickSavePath: (suspend () -> String?)? = null,
    /** 挑一个"读哪一份"的路径。**读档在主界面那一边**，这里留着只是为了探针与测试好用。 */
    onPickOpenPath: (suspend () -> String?)? = null,
    /** 读进来那份存档要交给谁（主界面那一边会换掉整个会话）。 */
    onOpen: (suspend (String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    var acted by remember { mutableStateOf("") }
    var slots by remember { mutableStateOf("1") }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            TopBar(
                session = session,
                onPickSavePath = onPickSavePath,
                onPickOpenPath = onPickOpenPath,
                onOpen = onOpen,
                scope = scope,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapPane(session, Modifier.weight(1f).fillMaxHeight())
                ThingsPane(session, Modifier.weight(1.15f).fillMaxHeight())
                LogPane(session, Modifier.weight(1.2f).fillMaxHeight())
            }
            Spacer(Modifier.height(8.dp))
            /*
             * **那条弹出来的提示**（§14.33）。现在只有一种情况会置它：**硬圈里那块地长不出来**。
             *
             * 别的失败都只写日志就够——那是玩家自己那一轮没成，他本来就盯着日志看。这一条不一样：它是
             * 世界少了一块地，而玩家可能正站在缺口边上，看见的是"这里什么都没有"。而日志会被后面几十行
             * 盖掉，所以它需要一个关掉才消失的地方。
             */
            session.notice?.let { message ->
                NoticeBar(message, onDismiss = { session.dismissNotice() })
                Spacer(Modifier.height(8.dp))
            }
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
                slots = slots,
                busy = session.busy,
                tick = session.tickLabel,
                onSaid = { said = it },
                onActed = { acted = it },
                onSlots = { slots = it.filter { c -> c.isDigit() }.take(3) },
                onSay = {
                    session.sayNow(scope, said)
                    said = ""
                },
                onNextTurn = {
                    session.nextTurn(scope, acted)
                    acted = ""
                },
                onPassSlots = {
                    session.passSlots(scope, slots.toIntOrNull() ?: 1)
                },
            )
        }
    }
}

// ── 顶栏 ────────────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    session: GameSession,
    onPickSavePath: (suspend () -> String?)?,
    onPickOpenPath: (suspend () -> String?)?,
    onOpen: (suspend (String) -> Unit)?,
    scope: CoroutineScope,
) {
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
         * **「等到下一件事」那个按钮已经去掉了**（§14.51）。
         *
         * 它是"过足够大的 N 格"——而「过 N 格」碰上时间轴上有事到期会停在那一刻，两条机制做的是同一件
         * 事。留着它只会让人不知道该按哪个，而"没有可等的"那句禁用状态又正好是「过 N 格」能照常走的
         * 情形（过一格本来就该走，哪怕时间轴上什么都没有）。
         *
         * 而"下一件事在什么时候"这件事仍然看得见：它就印在「下一轮」那个按钮旁边那句话里。
         */
        val next = session.nextStop
        if (next != null) {
            Text(
                "（时间轴上下一件事：${next.short}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        /*
         * 「让导演排一排」（§8）。
         *
         * 它是一个**手动**的触发，而设计里导演该由两类事件唤醒（时间轴到期、剧情跑偏，§8.2）。
         * 现在手动按，是因为那两条路都还要先有一个消费者才谈得上——而队列（§8.3.2）要解决的问题
         * （并发写、积压、"世界中…"的提示）在只有一个调用点的时候都还不存在。
         */
        TextButton(
            onClick = { session.arrange(scope) },
            enabled = !session.busy && session.pending == null,
        ) {
            Text("让导演排一排")
        }
        /*
         * `存`（§14.49）。
         *
         * 三件事都在这里说清：
         *  - **没选过文件时按它 = 现在选一个**，选完立刻写一份（不是"选了之后等下回合自动存"——玩家按下
         *    去是想现在就有个文件）。
         *  - **选过之后它就只是"再存一份"**；而每一回合结束的自动存档也写向同一个文件（会话那一侧
         *    `autoSave`），于是这个按钮更像是"我怕刚才那一下没存上，再按一次"。
         *  - **入口没给挑文件的能力时它不出现**（`onPickSavePath == null`）：按钮出现了却没反应比没有
         *    这个按钮更糟。
         */
        if (onPickSavePath != null) {
            TextButton(
                enabled = !session.busy,
                onClick = {
                    scope.launch {
                        val path = session.savingTo ?: onPickSavePath()
                        if (path != null) {
                            try {
                                session.saveTo(path)
                                session.announce("存到 ${path.substringAfterLast('/').substringAfterLast('\\')} 了。")
                            } catch (cause: Throwable) {
                                session.announce("没存成：${cause.message ?: cause::class.simpleName}")
                            }
                        }
                    }
                },
                content = { Text(if (session.savingTo == null) "存…" else "存") },
            )
        }
        /*
         * `读取`（§14.49）。**它默认不出现**：用户定的形状是"读档只在主界面"。
         *
         * 留着这个入口是因为探针与测试要在一个会话里验"读进来的世界真的换了"——而那件事在界面上不必
         * 给玩家看。
         */
        if (onPickOpenPath != null && onOpen != null) {
            TextButton(
                enabled = !session.busy,
                onClick = {
                    scope.launch {
                        val path = onPickOpenPath()
                        if (path != null) onOpen(path)
                    }
                },
                content = { Text("读取…") },
            )
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
        /*
         * **任务清单，一件事一行**（§8.3.2、§14.33）。
         *
         * 它取代了原来那一句「跑着…」的字符串：判定在跑、地形在后台长、导演在看着，这三件事可以同时
         * 成立，而一个字符串只能显示其中一件。玩家看到「算后果…」和「正在生成地形」同时在列，也就
         * 知道自己的动作在等什么。
         */
        val running = session.tasks
        if (running.isEmpty() && session.busy) {
            // 兜底：有一个动作跑着却没有登记任务。**这是接线漏了，不是正常状态**——写出来为了让
            // 它一眼可辨，而不是让界面看着像卡住了。
            Text(
                session.status.ifBlank { "跑着…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            running.forEach { task ->
                Text(
                    "· ${task.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

/**
 * 那条弹出来的提示。**要玩家自己关掉**——它是"世界里少了一块地"这一类事实，不该自己消失。
 */
@Composable
private fun NoticeBar(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

// ── 地图 ────────────────────────────────────────────────────────────────────────────

/**
 * 俯视图。**画的是"这一层地面的平面图"**：
 *
 * - 大点 = 站在地上的东西（米格层 0）
 * - 小点 = 摆在它们上面的东西（米格层不是地面那一层，且米格与某个地面东西重合）
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
    // **地面那一层由世界自己算**（默认米格 128，因为地面在 12800 厘米）。写死 0 会让整座酒馆变成
    // "摆件"，而症状是俯视图空空如也。
    val layer = session.world.groundMetreCell()
    val ground = entities.filter { it.cell().z == layer }
    val satellites = entities.filter { e ->
        e.cell().z != layer && ground.any { g -> g.cell().x == e.cell().x && g.cell().y == e.cell().y }
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

            // **说话的气泡**（§5.3）。谁说了话，就在谁的头上冒一句，而这一句**只存在当前回合**
            // ——[GameSession.speech] 每一轮开头清空，所以它不会变成一串越挂越多的历史。
            //
            // 画在名字之后，所以它盖在名字上面；位置往上推开自己的高度，一句话和五句话都不会
            // 把底下那个圆点盖住。
            session.speech.forEach { spoken ->
                val speaker = ground.firstOrNull { it.id == spoken.whoId } ?: return@forEach
                SpeechBubble(spoken, at(speaker.cell().x, speaker.cell().y), cell)
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

/**
 * 地图上的一句话（§5.3）。
 *
 * 位置一律在说话的人**头上**：先量一次自己的高度，再按高度往上推。不量的话多行的话会盖住底下那个
 * 圆点，而盖住的正好是"这句话是谁说的"。
 *
 * **它不做气泡尾巴、不做动画。** 这一版界面刻意只做"看得出来谁说了什么"（见文件头那三条），
 * 好看留给后面。
 */
@Composable
private fun SpeechBubble(spoken: Spoken, pos: Offset, cell: Float) {
    val density = LocalDensity.current
    val widthPx = (cell * 2.6f).coerceAtLeast(120f)
    var heightPx by remember { mutableStateOf(0) }
    val shape = RoundedCornerShape(6.dp)

    Box(
        Modifier
            .offset {
                IntOffset(
                    (pos.x - widthPx / 2f).roundToInt(),
                    (pos.y - heightPx - 10f).roundToInt(),
                )
            }
            .width(with(density) { widthPx.toDp() })
            .onSizeChanged { heightPx = it.height }
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            spoken.text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * 底部那一条（§14.51）。
 *
 * ## 三个入口，各是各的东西
 *
 *  - **说**：按下去立刻发出去（或者按回车）——**它不推进时间、不占名额**。对象会当场回你，而世界停在
 *    原处。这就是用户那句"说话不过轮次"。
 *  - **做**：写在那儿**攒着**，与队列里其他角色的动作一起，等「下一轮」提交。它推进一格。
 *  - **过 N 格**：只走时间，不叫醒任何人——碰上时间轴上有事到期就停在那一刻。
 *
 * 「等到下一件事」那个按钮**已经去掉了**：它就是"过足够大的 N 格"（`passSlots` 碰上到期会停），
 * 两条机制做同一件事只会让人不知道该按哪个。
 */
@Composable
private fun InputBar(
    said: String,
    acted: String,
    slots: String,
    busy: Boolean,
    tick: String,
    onSaid: (String) -> Unit,
    onActed: (String) -> Unit,
    onSlots: (String) -> Unit,
    onSay: () -> Unit,
    onNextTurn: () -> Unit,
    onPassSlots: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = said,
                onValueChange = onSaid,
                modifier = Modifier.weight(1f),
                label = { Text("说（立刻，不过时间）") },
                placeholder = { Text("「这酒怎么这么淡？」") },
                singleLine = true,
                enabled = !busy,
                keyboardActions = KeyboardActions(onDone = { onSay() }),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            )
            Button(onClick = onSay, enabled = !busy && said.isNotBlank()) {
                Text("说")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = acted,
                onValueChange = onActed,
                modifier = Modifier.weight(1f),
                label = { Text("做（攒着，下一轮提交）") },
                placeholder = { Text("我拿起桌上那杯麦酒") },
                singleLine = true,
                enabled = !busy,
            )
            Button(onClick = onNextTurn, enabled = !busy) {
                Text(if (busy) "…" else "下一轮（$tick）")
            }
            OutlinedTextField(
                value = slots,
                onValueChange = onSlots,
                modifier = Modifier.width(90.dp),
                label = { Text("格数") },
                singleLine = true,
                enabled = !busy,
            )
            TextButton(onClick = onPassSlots, enabled = !busy && (slots.toIntOrNull() ?: 0) > 0) {
                Text("过")
            }
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
