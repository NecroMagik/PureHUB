package com.necromagik.purehub.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Универсальная постраничная сетка папки с поддержкой:
 * 1) Перетаскивания иконок внутри текущей вкладки
 * 2) Перетаскивания иконок МЕЖДУ вкладками (при поднесении к краям карточки или кнопками)
 * 3) Горизонтального и вертикального свайпа с морфинг-индикатором OxygenOS
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FolderInteractivePagerGrid(
    apps: List<InstalledAppItem>,
    itemsPerPage: Int,
    isHorizontalSwipe: Boolean,
    showLabels: Boolean,
    showEditBadges: Boolean,
    accentColor: Color,
    onMoveApp: (fromGlobalIndex: Int, toGlobalIndex: Int) -> Unit,
    onRemoveApp: ((String) -> Unit)? = null,
    onAppClick: ((String) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val currentApps by rememberUpdatedState(apps)
    val currentOnMove by rememberUpdatedState(onMoveApp)

    val safeItemsPerPage = itemsPerPage.coerceAtLeast(1)
    val pages = remember(apps, safeItemsPerPage) {
        if (apps.isEmpty()) listOf(emptyList()) else apps.chunked(safeItemsPerPage)
    }
    val pageCount = pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // Глобальное состояние перетаскивания (живет поверх всех вкладок пейджера!)
    var draggingPkg by remember { mutableStateOf<String?>(null) }
    var dragFingerRootPos by remember { mutableStateOf(Offset.Zero) }
    var lastEdgeTurnTimestamp by remember { mutableLongStateOf(0L) }

    var containerRootPos by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Координаты всех слотов на текущем экране в глобальных координатах окна
    val slotRootBounds = remember { mutableStateMapOf<String, Pair<Offset, IntSize>>() }

    val draggedItem = remember(draggingPkg, apps) {
        apps.firstOrNull { it.packageName == draggingPkg }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Верхняя подсказка / быстрые кнопки переброса между слайдами при активном редактировании
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Вкладка ${pagerState.currentPage + 1} из $pageCount",
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = if (pageCount > 1) "Тяните к краю для переноса на др. вкладку" else "Зажмите иконку для сортировки",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (showLabels) 296.dp else 250.dp)
                .onGloballyPositioned { coords ->
                    containerRootPos = coords.positionInRoot()
                    containerSize = coords.size
                },
            contentAlignment = Alignment.Center
        ) {
            // Боковые подсветки-триггеры при перетаскивании к краю для смены вкладки
            if (draggingPkg != null && pageCount > 1) {
                if (pagerState.currentPage > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(26.dp)
                            .fillMaxHeight(0.8f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("‹", color = accentColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (pagerState.currentPage < pageCount - 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(26.dp)
                            .fillMaxHeight(0.8f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("›", color = accentColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val pageContent: @Composable (Int) -> Unit = { pageIdx ->
                val slideApps = pages.getOrElse(pageIdx) { emptyList() }
                val pageStartGlobalIdx = pageIdx * safeItemsPerPage

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val rows = slideApps.chunked(3)
                    for (rowIndex in 0 until 3) {
                        val rowApps = rows.getOrElse(rowIndex) { emptyList() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (colIndex in 0 until 3) {
                                val app = rowApps.getOrNull(colIndex)
                                if (app != null) {
                                    val localIdx = rowIndex * 3 + colIndex
                                    val globalIndex = pageStartGlobalIdx + localIdx
                                    val isBeingDragged = draggingPkg == app.packageName

                                    key(app.packageName) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .padding(4.dp)
                                                .onGloballyPositioned { coords ->
                                                    slotRootBounds[app.packageName] =
                                                        coords.positionInRoot() to coords.size
                                                }
                                                .graphicsLayer {
                                                    alpha = if (isBeingDragged) 0.25f else 1f
                                                }
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(
                                                    if (isBeingDragged) accentColor.copy(alpha = 0.18f)
                                                    else Color.White.copy(alpha = 0.05f)
                                                )
                                                .border(
                                                    width = if (isBeingDragged) 1.5.dp else 0.dp,
                                                    color = if (isBeingDragged) accentColor else Color.Transparent,
                                                    shape = RoundedCornerShape(16.dp)
                                                )
                                                .clickable(enabled = draggingPkg == null && onAppClick != null) {
                                                    onAppClick?.invoke(app.packageName)
                                                }
                                                .pointerInput(app.packageName) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { localOffset ->
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            val slotInfo = slotRootBounds[app.packageName]
                                                            if (slotInfo != null) {
                                                                draggingPkg = app.packageName
                                                                dragFingerRootPos = slotInfo.first + localOffset
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            draggingPkg = null
                                                        },
                                                        onDragCancel = {
                                                            draggingPkg = null
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            dragFingerRootPos += dragAmount

                                                            // 1. Проверяем удержание у края для ПЕРЕЛИСТЫВАНИЯ МЕЖДУ ВКЛАДКАМИ
                                                            val now = System.currentTimeMillis()
                                                            val relX = dragFingerRootPos.x - containerRootPos.x
                                                            val relY = dragFingerRootPos.y - containerRootPos.y
                                                            val edgeThresholdPx = 58f

                                                            if (pageCount > 1 && now - lastEdgeTurnTimestamp > 650L) {
                                                                val wantPrev = if (isHorizontalSwipe) {
                                                                    relX < edgeThresholdPx
                                                                } else {
                                                                    relY < edgeThresholdPx
                                                                }
                                                                val wantNext = if (isHorizontalSwipe) {
                                                                    relX > containerSize.width - edgeThresholdPx
                                                                } else {
                                                                    relY > containerSize.height - edgeThresholdPx
                                                                }

                                                                if (wantPrev && pagerState.currentPage > 0) {
                                                                    lastEdgeTurnTimestamp = now
                                                                    val targetPage = pagerState.currentPage - 1
                                                                    val fromIdx = currentApps.indexOfFirst { it.packageName == app.packageName }
                                                                    val toIdx = ((targetPage + 1) * safeItemsPerPage - 1)
                                                                        .coerceIn(0, currentApps.lastIndex)
                                                                    if (fromIdx != -1 && fromIdx != toIdx) {
                                                                        currentOnMove(fromIdx, toIdx)
                                                                    }
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    scope.launch {
                                                                        pagerState.animateScrollToPage(targetPage)
                                                                    }
                                                                    return@detectDragGesturesAfterLongPress
                                                                } else if (wantNext && pagerState.currentPage < pageCount - 1) {
                                                                    lastEdgeTurnTimestamp = now
                                                                    val targetPage = pagerState.currentPage + 1
                                                                    val fromIdx = currentApps.indexOfFirst { it.packageName == app.packageName }
                                                                    val toIdx = (targetPage * safeItemsPerPage)
                                                                        .coerceIn(0, currentApps.lastIndex)
                                                                    if (fromIdx != -1 && fromIdx != toIdx) {
                                                                        currentOnMove(fromIdx, toIdx)
                                                                    }
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    scope.launch {
                                                                        pagerState.animateScrollToPage(targetPage)
                                                                    }
                                                                    return@detectDragGesturesAfterLongPress
                                                                }
                                                            }

                                                            // 2. Проверяем пересечение с другими иконками на открытой вкладке
                                                            val visiblePagePkgs = pages
                                                                .getOrElse(pagerState.currentPage) { emptyList() }
                                                                .map { it.packageName }
                                                                .toSet()

                                                            val targetEntry = slotRootBounds.entries.firstOrNull { (otherPkg, pair) ->
                                                                if (otherPkg == app.packageName || !visiblePagePkgs.contains(otherPkg)) {
                                                                    return@firstOrNull false
                                                                }
                                                                val (pos, size) = pair
                                                                val otherCenter = Offset(
                                                                    x = pos.x + size.width / 2f,
                                                                    y = pos.y + size.height / 2f
                                                                )
                                                                hypot(
                                                                    dragFingerRootPos.x - otherCenter.x,
                                                                    dragFingerRootPos.y - otherCenter.y
                                                                ) < size.width * 0.46f
                                                            }

                                                            if (targetEntry != null) {
                                                                val fromIdx = currentApps.indexOfFirst { it.packageName == app.packageName }
                                                                val toIdx = currentApps.indexOfFirst { it.packageName == targetEntry.key }
                                                                if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                    currentOnMove(fromIdx, toIdx)
                                                                }
                                                            }
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            FolderAppCellContent(
                                                app = app,
                                                globalIndex = globalIndex,
                                                showLabels = showLabels,
                                                showEditBadges = showEditBadges,
                                                accentColor = accentColor,
                                                onRemoveApp = onRemoveApp
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    }
                }
            }

            if (isHorizontalSwipe) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = draggingPkg == null,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    pageContent(pageIdx)
                }
            } else {
                VerticalPager(
                    state = pagerState,
                    userScrollEnabled = draggingPkg == null,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    pageContent(pageIdx)
                }
            }

            // Парящая иконка под пальцем во время перетаскивания (следует за пальцем даже при смене вкладки!)
            if (draggedItem != null) {
                val localOverlayOffset = dragFingerRootPos - containerRootPos
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                x = (localOverlayOffset.x - 42.dp.toPx()).toInt(),
                                y = (localOverlayOffset.y - 42.dp.toPx()).toInt()
                            )
                        }
                        .size(84.dp)
                        .zIndex(50f)
                        .graphicsLayer {
                            scaleX = 1.18f
                            scaleY = 1.18f
                            shadowElevation = 18f
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .background(accentColor.copy(alpha = 0.28f))
                        .border(2.dp, accentColor, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = remember(draggedItem.packageName) {
                        PureFolderWidgetProvider.drawableToBitmap(draggedItem.icon, 144).asImageBitmap()
                    }
                    Image(
                        bitmap = bmp,
                        contentDescription = draggedItem.label,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }
        }

        // Динамические перетекающие точки в стиле OxygenOS
        if (pageCount > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            OxygenMorphingDotsBar(
                pagerState = pagerState,
                pageCount = pageCount,
                activeColor = accentColor
            )
        }
    }
}

@Composable
private fun FolderAppCellContent(
    app: InstalledAppItem,
    globalIndex: Int,
    showLabels: Boolean,
    showEditBadges: Boolean,
    accentColor: Color,
    onRemoveApp: ((String) -> Unit)?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(modifier = Modifier.size(54.dp)) {
            val bmp = remember(app.packageName) {
                PureFolderWidgetProvider.drawableToBitmap(app.icon, 144).asImageBitmap()
            }
            Image(
                bitmap = bmp,
                contentDescription = app.label,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
            )

            if (showEditBadges) {
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = "${globalIndex + 1}",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }

                if (onRemoveApp != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFFF5252),
                        modifier = Modifier
                            .size(18.dp)
                            .align(Alignment.TopEnd)
                            .clickable { onRemoveApp(app.packageName) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "✕",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        if (showLabels) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OxygenMorphingDotsBar(
    pagerState: PagerState,
    pageCount: Int,
    activeColor: Color
) {
    val currentPage = pagerState.currentPage
    val offsetFraction = pagerState.currentPageOffsetFraction

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(10.dp)
    ) {
        for (i in 0 until pageCount) {
            val distance = abs((currentPage + offsetFraction) - i).coerceIn(0f, 1f)
            val activeFraction = 1f - distance
            val widthDp = 7.dp + (16.dp * activeFraction)
            val dotColor = lerp(
                Color.White.copy(alpha = 0.3f),
                activeColor,
                activeFraction
            )

            Box(
                modifier = Modifier
                    .width(widthDp)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}