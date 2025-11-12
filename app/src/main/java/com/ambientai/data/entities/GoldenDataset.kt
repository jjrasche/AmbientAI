package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class GoldenDataset(
    @Id var id: Long = 0,
    var audioFilePath: String,
    var transcript: String,
    var timestamp: Long,
    var matchedWorkflow: String = "",
    var wasAccurate: Boolean? = null
)
