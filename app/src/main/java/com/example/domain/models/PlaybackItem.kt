package com.example.domain.models

data class PlaybackItem(
    val id: String,
    val nome: String,
    val tipo: String, // "image", "video", "website", etc.
    val url: String?,
    val storage_path: String?,
    val duracao: Int,
    val ordem: Int,
    val cache_path: String?,
    val conteudo_online: Boolean
)
