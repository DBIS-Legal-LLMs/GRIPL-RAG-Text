export interface AnalysisRequest {
    bpmnXml: String
}

export interface AnalysisResponse {
    criticalElements: CriticalElement[];
    ragContext?: Record<string, RagElementContext>;
}

export interface CriticalElement {
    id: string;
    name: string;
    reason: string;
    references?: LlmReference[];
}

export interface LlmReference {
    exactText: string;
    sourceDocument?: string | null;
}

export interface RagElementContext {
    activityName: string | null;
    entities: RagEntity[];
    relationships: RagRelationship[];
    documents: RagDocument[];
}

export interface RagEntity {
    label: string;
    type: string;
    description: string;
}

export interface RagRelationship {
    source: string;
    target: string;
    label: string;
}

export interface RagDocument {
    content: string;
    preview: string;
    sourceDocument?: string | null;
}