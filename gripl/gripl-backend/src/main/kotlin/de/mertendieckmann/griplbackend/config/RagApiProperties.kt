package de.mertendieckmann.griplbackend.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "rag.api")
@Validated
class RagApiProperties {
    @NotBlank
    lateinit var url: String
}
