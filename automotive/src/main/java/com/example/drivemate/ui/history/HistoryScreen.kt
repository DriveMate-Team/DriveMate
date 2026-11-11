package com.example.drivemate.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.drivemate.data.DriveRepository
import com.example.drivemate.ui.TripSummary
import com.example.drivemate.ui.components.DmButton
import com.example.drivemate.ui.components.DmCard
import com.example.drivemate.ui.components.DmConfirmDialog
import com.example.drivemate.ui.summarize
import com.example.drivemate.ui.theme.DmDimens
import com.example.drivemate.ui.theme.DmTheme
import com.example.drivemate.ui.theme.DmType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 기록 목록 화면의 ViewModel: 지난 주행들을 최신순으로 보여주고, 삭제를 처리한다 */
@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: DriveRepository) : ViewModel() {
    val trips: StateFlow<List<TripSummary>> =
        combine(repo.observeFinishedTrips(), repo.observeAllEvents()) { trips, events ->
            trips.map { summarize(it, events) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 주행 하나 삭제 */
    fun delete(id: Long) { viewModelScope.launch { repo.deleteTrip(id) } }

    /** 전체 삭제 (모든 주행 기록 + 다음 목표) */
    fun deleteAll() { viewModelScope.launch { repo.deleteEverything() } }
}

/**
 * 기록 목록 화면 (정차 중에만 보인다).
 * 날짜·목적지·주요 숫자가 들어간 주행 카드를 최신순으로 보여준다.
 * 카드를 누르면 그 주행의 리포트, [삭제]로 주행 단위 삭제, [전체 삭제]로 모두 삭제.
 * (개인정보 원칙: 필요한 것만 모으고, 사용자가 지울 수 있게 한다)
 */
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val colors = DmTheme.colors
    var deleteTarget by remember { mutableStateOf<TripSummary?>(null) }   // 지울지 물어보는 중인 주행
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(DmDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(DmDimens.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
            DmButton("뒤로", onBack, primary = false)
            Text("기록 목록", style = DmType.title, color = colors.text, modifier = Modifier.weight(1f))
            DmButton("전체 삭제", { confirmDeleteAll = true }, primary = false, enabled = trips.isNotEmpty())
        }

        if (trips.isEmpty()) {
            DmCard(Modifier.fillMaxWidth()) {
                Text("아직 주행 기록이 없어요", style = DmType.body, color = colors.text)
            }
        }

        // LazyColumn: 화면에 보이는 카드만 만들어서 기록이 많아도 가볍다
        LazyColumn(verticalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
            items(trips, key = { it.id }) { trip ->
                DmCard(Modifier.fillMaxWidth().clickable { onOpen(trip.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DmDimens.gap)) {
                        Column(Modifier.weight(1f)) {
                            Text(trip.date, style = DmType.sub, color = colors.textSub)
                            Text("${trip.destination} · ${trip.meta}", style = DmType.body, color = colors.text)
                            Text(trip.numbers, style = DmType.sub, color = colors.textSub)
                        }
                        DmButton("삭제", { deleteTarget = trip }, primary = false)
                    }
                }
            }
        }
    }

    deleteTarget?.let { trip ->
        DmConfirmDialog(
            title = "이 주행 기록을 지울까요?",
            message = "${trip.date} 주행의 기록이 모두 지워져요.",
            confirmText = "지우기",
            onConfirm = { viewModel.delete(trip.id); deleteTarget = null },
            onDismiss = { deleteTarget = null },
        )
    }
    if (confirmDeleteAll) {
        DmConfirmDialog(
            title = "모든 기록을 지울까요?",
            message = "주행 기록과 다음 연습 목표가 모두 지워지고 되돌릴 수 없어요.",
            confirmText = "모두 지우기",
            onConfirm = { viewModel.deleteAll(); confirmDeleteAll = false },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}
