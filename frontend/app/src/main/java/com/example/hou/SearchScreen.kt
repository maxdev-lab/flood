package com.example.hou

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hou.data.model.PoiItem

/**
 * 전체화면 길찾기 검색 UI.
 *
 * @param originQuery      출발지 텍스트
 * @param destQuery        도착지 텍스트
 * @param activeField      현재 포커스된 필드 (ORIGIN / DEST)
 * @param searchResults    실시간 검색 결과 (타이핑 중일 때)
 * @param searchHistory    검색 기록 (최신순)
 * @param onOriginChange   출발지 텍스트 변경
 * @param onDestChange     도착지 텍스트 변경
 * @param onFieldFocus     필드 포커스 변경
 * @param onSwapFields     출발지↔도착지 교체
 * @param onUseMyLocation  '내 위치' 선택 → 출발지에 현재 위치 입력
 * @param onPoiSelected    POI 항목 선택 (어느 필드든)
 * @param onFindRoute      길찾기 버튼
 * @param onDeleteHistory  기록 항목 개별 삭제
 * @param onClearHistory   기록 전체 삭제
 * @param onBack           뒤로가기 (검색 화면 닫기)
 */

enum class SearchField { ORIGIN, DEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    originQuery: String,
    destQuery: String,
    activeField: SearchField,
    searchResults: List<PoiItem>,
    searchHistory: List<String>,
    isSearching: Boolean,
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
    onBack: () -> Unit,
) {
    val originFocus = remember { FocusRequester() }
    val destFocus   = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 어느 필드가 활성인지에 따라 보여줄 쿼리
    val activeQuery = if (activeField == SearchField.ORIGIN) originQuery else destQuery
    val showResults = activeQuery.isNotBlank() && searchResults.isNotEmpty()
    val showHistory = activeQuery.isBlank() && searchHistory.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // ── 상단 헤더 + 입력창 카드 ──────────────────────
        Surface(
            shadowElevation = 4.dp,
            color = Color.White
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

                Spacer(modifier = Modifier.height(24.dp))

                // 뒤로가기 + 타이틀
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "닫기",
                            tint = Color(0xFF444444)
                        )
                    }
                    Text(
                        "길찾기",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111111)
                    )
                }

                // 출발지 / 도착지 입력 영역
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 점선 인디케이터
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1976D2))
                        )
                        repeat(4) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFBBBBBB))
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFD32F2F))
                        )
                    }

                    // 입력 필드 2개
                    Column(modifier = Modifier.weight(1f)) {
                        // 출발지
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (activeField == SearchField.ORIGIN)
                                        Color(0xFFE8F0FE) else Color(0xFFF5F5F5)
                                )
                        ) {
                            OutlinedTextField(
                                value = originQuery,
                                onValueChange = onOriginChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(originFocus)
                                    .onFocusChanged { if (it.isFocused) onFieldFocus(SearchField.ORIGIN) },
                                placeholder = {
                                    Text("출발지 입력", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                                },
                                trailingIcon = {
                                    if (originQuery.isNotEmpty()) {
                                        IconButton(onClick = { onOriginChange("") }) {
                                            Icon(Icons.Default.Clear, null,
                                                tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor   = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { destFocus.requestFocus() }),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // 도착지
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (activeField == SearchField.DEST)
                                        Color(0xFFE8F0FE) else Color(0xFFF5F5F5)
                                )
                        ) {
                            OutlinedTextField(
                                value = destQuery,
                                onValueChange = onDestChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(destFocus)
                                    .onFocusChanged { if (it.isFocused) onFieldFocus(SearchField.DEST) },
                                placeholder = {
                                    Text("도착지 입력", color = Color(0xFFAAAAAA), fontSize = 15.sp)
                                },
                                trailingIcon = {
                                    if (destQuery.isNotEmpty()) {
                                        IconButton(onClick = { onDestChange("") }) {
                                            Icon(Icons.Default.Clear, null,
                                                tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                                        }
                                    }
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor   = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onFindRoute() }),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                            )
                        }
                    }

                    // 교체 버튼
                    IconButton(
                        onClick = onSwapFields,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF0F0F0))
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = "출발지/도착지 교체",
                            tint = Color(0xFF555555)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 하단 버튼 행: 내 위치 | 길찾기
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 내 위치 버튼
                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            onUseMyLocation()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1976D2))
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("내 위치", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    // 길찾기 버튼
                    Button(
                        onClick = onFindRoute,
                        enabled = originQuery.isNotBlank() && destQuery.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2),
                            disabledContainerColor = Color(0xFFBBBBBB)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("길찾기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── 본문: 실시간 검색결과 or 검색 기록 ──────────────
        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // 로딩 인디케이터
            if (isSearching) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF1976D2),
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            }

            // 실시간 검색 결과
            if (showResults && !isSearching) {
                items(searchResults) { poi ->
                    PoiResultRow(
                        poi = poi,
                        onClick = {
                            focusManager.clearFocus()
                            onPoiSelected(poi, activeField)
                        }
                    )
                    HorizontalDivider(
                        color = Color(0xFFF0F0F0),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // 검색 기록
            if (showHistory && !isSearching) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "최근 검색",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF888888)
                        )
                        TextButton(onClick = onClearHistory) {
                            Text(
                                "전체 삭제",
                                fontSize = 12.sp,
                                color = Color(0xFF888888)
                            )
                        }
                    }
                }
                items(searchHistory) { query ->
                    HistoryRow(
                        query = query,
                        onClick = {
                            focusManager.clearFocus()
                            onHistorySelected(query)
                        },
                        onDelete = { onDeleteHistory(query) }
                    )
                    HorizontalDivider(
                        color = Color(0xFFF0F0F0),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // 빈 상태 안내
            if (!showResults && !showHistory && !isSearching && activeQuery.isBlank()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "장소, 주소를 검색해보세요",
                            color = Color(0xFFBBBBBB),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

// ── POI 결과 행 ──────────────────────────────────
@Composable
private fun PoiResultRow(poi: PoiItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = Color(0xFF1976D2),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                poi.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF111111),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (poi.getFullAddress().isNotBlank()) {
                Text(
                    poi.getFullAddress(),
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── 검색 기록 행 ──────────────────────────────────
@Composable
private fun HistoryRow(query: String, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.History,
            contentDescription = null,
            tint = Color(0xFFAAAAAA),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            query,
            fontSize = 15.sp,
            color = Color(0xFF333333),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Clear,
                contentDescription = "삭제",
                tint = Color(0xFFCCCCCC),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
