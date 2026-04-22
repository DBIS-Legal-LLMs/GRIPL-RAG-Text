import {RagElementContext} from "@/models/dto/AnalysisDto";
import {Badge} from "@/components/ui/badge";
import {BookOpen, GitBranch, Landmark} from "lucide-react";

interface RagContextCardProps {
    ragContext: Record<string, RagElementContext>;
    selectedElementId?: string | null;
}

export default function RagContextCard({ ragContext, selectedElementId }: RagContextCardProps) {
    const entries = Object.entries(ragContext);

    if (entries.length === 0) {
        return <p className="text-sm text-muted-foreground p-4">No RAG context was retrieved for any activity.</p>
    }

    return (
        <div className="max-h-[400px] overflow-y-auto space-y-4 p-1">
            {entries.map(([elementId, ctx]) => {
                const isSelected = elementId === selectedElementId;
                const hasContent = ctx.entities.length > 0 || ctx.relationships.length > 0 || ctx.documents.length > 0;

                return (
                    <div
                        key={elementId}
                        className={`rounded-lg border p-3 space-y-2 transition-colors ${
                            isSelected ? "border-primary bg-primary/5" : "border-border"
                        }`}
                    >
                        {/* Activity header */}
                        <div className="flex items-center gap-2">
                            <span className="font-medium text-sm">
                                {ctx.activityName || elementId}
                            </span>
                            <Badge variant="secondary" className="text-xs">
                                {elementId}
                            </Badge>
                        </div>

                        {!hasContent && (
                            <p className="text-xs text-muted-foreground italic">
                                No context retrieved for this activity.
                            </p>
                        )}

                        {/* Entities */}
                        {ctx.entities.length > 0 && (
                            <div className="space-y-1">
                                <div className="flex items-center gap-1 text-xs font-semibold text-muted-foreground">
                                    <Landmark className="h-3 w-3" />
                                    Entities
                                </div>
                                <div className="flex flex-wrap gap-1">
                                    {ctx.entities.map((entity, i) => (
                                        <Badge key={i} variant="outline" className="text-xs font-normal">
                                            <span className="text-muted-foreground mr-1">[{entity.type}]</span>
                                            {entity.label}
                                        </Badge>
                                    ))}
                                </div>
                                {/* Show descriptions for entities that have them */}
                                {ctx.entities.filter(e => e.description).length > 0 && (
                                    <ul className="text-xs text-muted-foreground list-disc list-inside pl-1 space-y-0.5">
                                        {ctx.entities
                                            .filter(e => e.description)
                                            .slice(0, 5)
                                            .map((e, i) => (
                                                <li key={i}>
                                                    <span className="font-medium">{e.label}:</span>{" "}
                                                    {e.description}
                                                </li>
                                            ))}
                                    </ul>
                                )}
                            </div>
                        )}

                        {/* Relationships */}
                        {ctx.relationships.length > 0 && (
                            <div className="space-y-1">
                                <div className="flex items-center gap-1 text-xs font-semibold text-muted-foreground">
                                    <GitBranch className="h-3 w-3" />
                                    Relationships
                                </div>
                                <ul className="text-xs space-y-0.5 pl-1">
                                    {ctx.relationships.map((rel, i) => (
                                        <li key={i} className="flex items-center gap-1">
                                            <span className="font-medium">{rel.source}</span>
                                            <span className="text-muted-foreground">→</span>
                                            <span className="font-medium">{rel.target}</span>
                                            <span className="text-muted-foreground">({rel.label})</span>
                                        </li>
                                    ))}
                                </ul>
                            </div>
                        )}

                        {/* Documents */}
                        {ctx.documents.length > 0 && (
                            <div className="space-y-1">
                                <div className="flex items-center gap-1 text-xs font-semibold text-muted-foreground">
                                    <BookOpen className="h-3 w-3" />
                                    Legal Excerpts
                                </div>
                                <div className="space-y-1">
                                    {ctx.documents.map((doc, i) => (
                                        <blockquote
                                            key={i}
                                            className="text-xs text-muted-foreground border-l-2 border-primary/30 pl-2 italic"
                                        >
                                            {doc.preview}
                                        </blockquote>
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>
                );
            })}
        </div>
    );
}
