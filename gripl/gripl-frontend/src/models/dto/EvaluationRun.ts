export interface EvaluationRunRow {
    id: number;
    createdAt: string;
    completedAt: string | null;
    status: "in_progress" | "completed" | "failed";
}
