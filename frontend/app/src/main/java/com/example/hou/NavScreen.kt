package com.example.hou

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hou.data.model.PoiItem
import com.skt.tmap.TMapView

/**
 * 메인 화면 Composable.
 * 상태는 모두 MainActivity에서 주입받아 표시만 담당합니다.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun NavScreen(
    tMapView: TMapView,
    // UI 상태
    isNavigating: Boolean,
    isRouteReady: Boolean,
    isMockMode: Boolean,
    instruction: String,
    remainDistance: String,
    routeSummary: String,
    // 침수 히트맵
    isFloodVisible: Boolean,
    onFloodToggle: () -> Unit,
    floodBannerState: FloodBannerState = FloodBannerState.NONE,
    // 검색 화면
    showSearchScreen: Boolean,
    originQuery: String,
    destQuery: String,
    activeField: SearchField,
    searchResults: List<PoiItem>,
    searchHistory: List<String>,
    isSearching: Boolean,
    // 이벤트 콜백
    onSearchBarClick: () -> Unit,
    onOriginChange: (String) -> Unit,
    onDestChange: (String) -> Unit,
    onFieldFocus: (SearchField) -> Unit,
    onSwapFields: () -> Unit,
    onUseMyLocation: () -> Unit,
    onPoiSelected: (PoiItem, SearchField) -> Unit,
    onHistorySelected: (String) -> Unit,
    onFindRoute: () -> Unit,
    onDeleteHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSearchDismiss: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onClearRoute: () -> Unit,
    onMockModeChange: (Boolean) -> Unit,
    onFetchLocation: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // ── 지도 (항상 전체) ──
        AndroidView(
            factory = { tMapView },
            modifier = Modifier.fillMaxSize(),
            update = {
                it.setRotation(0f)
            }
        )

        // ── 탐색 전: 검색 바 (클릭하면 SearchScreen 열림) ──
        if (!isNavigating && !isRouteReady) {
            val borderBrush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF1976D2), Color(0xFF81D4FA))
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 56.dp)
                    .align(Alignment.TopCenter)
                    .border(width = 2.dp, brush = borderBrush, shape = RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(6.dp),
                onClick = onSearchBarClick
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF1976D2), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("어디로 갈까요?", color = Color(0xFFAAAAAA), fontSize = 16.sp)
                }
            }
        }

        // ── 상단 침수 플로팅 배너 (경로 준비 완료 or 안내 중 모두 표시) ──
        if (isRouteReady || isNavigating) {
            val bannerTopPadding = if (isNavigating) 120.dp else 56.dp
            AnimatedVisibility(
                visible = floodBannerState != FloodBannerState.NONE,
                enter = fadeIn() + expandVertically(),
                exit  = fadeOut() + shrinkVertically(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = bannerTopPadding)
                    .zIndex(10f)
            ) {
                when (floodBannerState) {
                    FloodBannerState.UNAVOIDABLE -> FloodUnavoidableBanner()
                    FloodBannerState.DETOUR_ACTIVE -> FloodDetourBanner()
                    else -> {}
                }
            }
        }

        // ── 경로 준비 완료: 하단 카드 ──
        if (isRouteReady && !isNavigating) {

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("차량 경로", fontSize = 13.sp, color = Color(0xFF1976D2), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(routeSummary, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.Black)
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF3F7FF), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("가상 주행 (테스트용)", fontSize = 14.sp, color = Color(0xFF1976D2), fontWeight = FontWeight.Medium)
                        Switch(
                            checked = isMockMode,
                            onCheckedChange = onMockModeChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF1976D2),
                                checkedTrackColor = Color(0xFFB3D1F5)
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onClearRoute,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("취소", fontSize = 15.sp, color = Color.DarkGray) }

                        Button(
                            onClick = onStartNavigation,
                            modifier = Modifier.weight(2f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("안내 시작", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        // ── 안내 중: 상단 지시 카드 ──
        if (isNavigating) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 52.dp)
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1976D2)),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Text(
                    text = instruction,
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, textAlign = TextAlign.Center
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(remainDistance, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onStopNavigation,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("안내 종료", fontWeight = FontWeight.Bold) }
                }
            }
        }

        // ── 침수 히트맵 범례 (레이어 켜져 있을 때만) ──
        if (isFloodVisible) {
            FloodLegend(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 12.dp,
                        bottom = when {
                            isNavigating  -> 120.dp
                            isRouteReady  -> 280.dp
                            else          -> 32.dp
                        }
                    )
            )
        }

        // ── 침수 히트맵 토글 FAB ──
        val locationFabBottom = when {
            isNavigating -> 120.dp
            isRouteReady -> 280.dp
            else         -> 32.dp
        }

        FloatingActionButton(
            onClick = onFloodToggle,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = locationFabBottom + 72.dp
                ),
            containerColor = if (isFloodVisible) Color(0xFFD32F2F) else Color.White,
            contentColor   = if (isFloodVisible) Color.White else Color(0xFFD32F2F),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(4.dp)
        ) {
            Text("💧", fontSize = 20.sp)
        }

        // ── 내 위치 FAB ──
        FloatingActionButton(
            onClick = onFetchLocation,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 16.dp,
                    bottom = locationFabBottom
                ),
            containerColor = Color.White,
            contentColor = Color(0xFF1976D2),
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(4.dp)
        ) {
            Icon(Icons.Default.LocationOn, "내 위치", modifier = Modifier.size(26.dp))
        }

        // ── 검색 전체화면 오버레이 ──
        if (showSearchScreen) {
            SearchScreen(
                originQuery       = originQuery,
                destQuery         = destQuery,
                activeField       = activeField,
                searchResults     = searchResults,
                searchHistory     = searchHistory,
                isSearching       = isSearching,
                onOriginChange    = onOriginChange,
                onDestChange      = onDestChange,
                onFieldFocus      = onFieldFocus,
                onSwapFields      = onSwapFields,
                onUseMyLocation   = onUseMyLocation,
                onPoiSelected     = onPoiSelected,
                onHistorySelected = onHistorySelected,
                onFindRoute       = onFindRoute,
                onDeleteHistory   = onDeleteHistory,
                onClearHistory    = onClearHistory,
                onBack            = onSearchDismiss,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// ★ 우회 불가 경고 배너 (목적지가 위험구역 내 or 우회 불가)
// ──────────────────────────────────────────────────────────────

@Composable
fun FloodUnavoidableBanner() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C)),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "⚠️  침수 위험 지역을 우회하기 어렵습니다. 이동에 주의하세요.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// ★ 우회 성공 안내 배너 (우회 경로로 안내 중)
// ──────────────────────────────────────────────────────────────

@Composable
fun FloodDetourBanner() {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0)),
        elevation = CardDefaults.cardElevation(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "🔀  침수 위험 지역을 우회한 경로로 안내합니다.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 침수 위험도 범례 컴포넌트
// ──────────────────────────────────────────────────────────────

@Composable
fun FloodLegend(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.92f)),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "침수 위험도",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF444444)
            )
            Spacer(modifier = Modifier.height(4.dp))

            // 그라데이션 바: 연노랑(30) → 짙은빨강(100)
            Box(
                modifier = Modifier
                    .width(90.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFFFF176),   // 연노랑 (위험도 30)
                                Color(0xFFFF9800),   // 주황   (위험도 ~60)
                                Color(0xFFB71C1C)    // 짙은빨강 (위험도 100)
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.width(90.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("주의", fontSize = 8.sp, color = Color(0xFF888888))
                Text("위험", fontSize = 8.sp, color = Color(0xFF888888))
            }
        }
    }
}
