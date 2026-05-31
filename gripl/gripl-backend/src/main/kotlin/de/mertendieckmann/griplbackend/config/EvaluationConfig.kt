package de.mertendieckmann.griplbackend.config

import de.mertendieckmann.griplbackend.evaluation.EvaluationRunner
import de.mertendieckmann.griplbackend.evaluation.MultiEvaluationRunner
import de.mertendieckmann.griplbackend.repository.DatasetRepository
import de.mertendieckmann.griplbackend.repository.EvaluationDataRepository
import de.mertendieckmann.griplbackend.repository.EvaluationRunRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EvaluationConfig {
    @Bean
    fun multiEvaluationRunner(
        singleRunner: EvaluationRunner,
        datasetRepository: DatasetRepository,
        evaluationDataRepository: EvaluationDataRepository,
        evaluationRunRepository: EvaluationRunRepository
    ) = MultiEvaluationRunner(singleRunner, datasetRepository, evaluationDataRepository, evaluationRunRepository)
}
