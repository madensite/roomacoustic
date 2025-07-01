package com.example.roomacoustic.viewmodel

import android.app.Application
import android.graphics.RectF
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.roomacoustic.data.AppDatabase
import com.example.roomacoustic.data.RoomEntity
import com.example.roomacoustic.repo.RoomRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RoomViewModel(app: Application) : AndroidViewModel(app) {

    /* ------------------------------------------------------------
       1) 데이터베이스 / 레포지터리  &  방 리스트 (챗봇 UI 등 기존 유지)
       ------------------------------------------------------------ */
    private val repo = RoomRepository(AppDatabase.get(app).roomDao())

    val rooms = repo.rooms.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val currentRoomId = MutableStateFlow<Int?>(null)
    fun select(roomId: Int) { currentRoomId.value = roomId }

    fun addRoom(title: String, onAdded: (Int) -> Unit) = viewModelScope.launch {
        onAdded(repo.add(title))
    }
    fun rename(id: Int, newTitle: String) = viewModelScope.launch { repo.rename(id, newTitle) }
    fun setMeasure(id: Int, flag: Boolean) = viewModelScope.launch { repo.setMeasure(id, flag) }
    fun setChat(id: Int, flag: Boolean)    = viewModelScope.launch { repo.setChat(id, flag) }
    fun delete(room: RoomEntity)           = viewModelScope.launch { repo.delete(room) }

    /* ------------------------------------------------------------
       2) MiDaS 측정값  (기존 그대로)
       ------------------------------------------------------------ */
    private val _measuredDimensions = MutableStateFlow<Triple<Float, Float, Float>?>(null)
    val measuredDimensions: StateFlow<Triple<Float, Float, Float>?> = _measuredDimensions.asStateFlow()
    fun setMeasuredRoomDimensions(w: Float, h: Float, d: Float) {
        _measuredDimensions.value = Triple(w, h, d)
    }

    /* ------------------------------------------------------------
       3) YOLOv8  결과  ★★ 추가 영역 ★★
       ------------------------------------------------------------ */
    /** 최근 프레임의 추론 시간(ms) */
    private val _inferenceTime = MutableStateFlow<Long?>(null)
    val inferenceTime: StateFlow<Long?> = _inferenceTime.asStateFlow()
    fun setInferenceTime(ms: Long) { _inferenceTime.value = ms }

    /** 스피커 바운딩박스 리스트 (UI 표시 & 후처리용) */
    private val _speakerBoxes = MutableStateFlow<List<RectF>>(emptyList())
    val speakerBoxes: StateFlow<List<RectF>> = _speakerBoxes.asStateFlow()
    fun setSpeakerBoxes(boxes: List<RectF>) { _speakerBoxes.value = boxes }

    /* ------------------------------------------------------------
       4) 종합 측정 결과(깊이+스피커)  (기존 구조 유지)
       ------------------------------------------------------------ */
    data class MeasureResult(
        val width: Float?,
        val height: Float?,
        val depth: Float?,
        val speakerBoxes: List<RectF>
    )
    private val _roomResult = MutableStateFlow<MeasureResult?>(null)
    val roomResult: StateFlow<MeasureResult?> = _roomResult.asStateFlow()
    fun setMeasureResult(w: Float?, h: Float?, d: Float?, boxes: List<RectF>) {
        _roomResult.value = MeasureResult(w, h, d, boxes)
    }

    fun deleteAllRooms() = viewModelScope.launch {
        repo.deleteAll()          // ← 레포지토리 호출
        currentRoomId.value = null   // 선택 방 초기화(선택되어 있었다면)
    }
}
