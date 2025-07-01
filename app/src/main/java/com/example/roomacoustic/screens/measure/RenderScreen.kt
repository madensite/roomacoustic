package com.example.roomacoustic.screens.measure

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.roomacoustic.navigation.Screen
import com.example.roomacoustic.viewmodel.RoomViewModel

@Composable
fun RenderScreen(
    nav: NavController,
    vm: RoomViewModel,      // Activity-Scoped ViewModel
    detected: Boolean       // ★ 새 파라미터
) {
    /* ---------- 방 ID 로딩 검사 ---------- */
    val roomId = vm.currentRoomId.collectAsState().value
    if (roomId == null) {
        Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        return
    }

    /* ---------- UI ---------- */
    Box(Modifier.fillMaxSize()) {

        /* 탐지 결과 배너 ------------------------------- */
        Text(
            text       = if (detected) "스피커 탐지 완료" else "스피커 미탐지",
            modifier   = Modifier
                .align(Alignment.Center),
            style      = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color      = if (detected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )

        /* 다음 버튼 ----------------------------------- */
        Button(
            onClick = { nav.navigate(Screen.TestGuide.route) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 50.dp)
        ) { Text("다음") }
    }
}
