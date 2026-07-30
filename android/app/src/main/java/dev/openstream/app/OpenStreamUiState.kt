package dev.openstream.app

/** A single vocabulary for the user-visible camera connection lifecycle. */
sealed interface OpenStreamUiState {
    data object Discovering : OpenStreamUiState
    data class Reserved(val slotLabel: String) : OpenStreamUiState
    data class Connecting(val targetLabel: String) : OpenStreamUiState
    data class Live(val targetLabel: String) : OpenStreamUiState
    data class Reconnecting(val slotLabel: String) : OpenStreamUiState
    data class Error(val message: String, val canRetry: Boolean = true) : OpenStreamUiState
    data object Stopped : OpenStreamUiState
}
