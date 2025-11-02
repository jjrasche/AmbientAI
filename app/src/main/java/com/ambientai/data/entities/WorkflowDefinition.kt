package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class WorkflowDefinition(
    @Id var id: Long = 0,
    var name: String,
    var definition: String,
    var enabled: Boolean = true
)