"use client";

import React, { useEffect, useState } from "react";
import { EvaluationRunRow } from "@/models/dto/EvaluationRun";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Download, Trash2, RefreshCw, ChevronDown, ChevronUp } from "lucide-react";

interface PastRunsPanelProps {
    onLoadRun: (runId: number) => void;
    isLoading: boolean;
}

export default function PastRunsPanel({ onLoadRun, isLoading }: PastRunsPanelProps) {
    const [runs, setRuns] = useState<EvaluationRunRow[]>([]);
    const [fetching, setFetching] = useState(false);
    const [open, setOpen] = useState(false);

    const fetchRuns = async () => {
        setFetching(true);
        try {
            const res = await fetch("/api/gdpr/evaluation/runs");
            if (res.ok) setRuns(await res.json());
        } finally {
            setFetching(false);
        }
    };

    useEffect(() => {
        fetchRuns();
    }, []);

    const handleDelete = async (id: number, e: React.MouseEvent) => {
        e.stopPropagation();
        if (!confirm(`Delete run #${id}?`)) return;
        await fetch(`/api/gdpr/evaluation/runs/${id}`, { method: "DELETE" });
        setRuns((prev) => prev.filter((r) => r.id !== id));
    };

    const statusBadge = (status: EvaluationRunRow["status"]) => {
        const cls = {
            completed: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
            in_progress: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900 dark:text-yellow-200",
            failed: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200",
        }[status];
        return <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${cls}`}>{status.replace("_", " ")}</span>;
    };

    return (
        <Card className="mb-6">
            <CardHeader className="py-3 px-4">
                <button
                    className="flex items-center justify-between w-full text-left"
                    onClick={() => setOpen((v) => !v)}
                >
                    <span className="font-semibold">Past Evaluation Runs ({runs.length})</span>
                    <div className="flex items-center gap-2">
                        <Button
                            variant="ghost"
                            size="sm"
                            disabled={fetching}
                            onClick={(e) => { e.stopPropagation(); fetchRuns(); }}
                        >
                            <RefreshCw className={`h-4 w-4 ${fetching ? "animate-spin" : ""}`} />
                        </Button>
                        {open ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                    </div>
                </button>
            </CardHeader>

            {open && (
                <CardContent className="px-4 pb-4">
                    {runs.length === 0 ? (
                        <p className="text-muted-foreground text-sm">No saved runs yet.</p>
                    ) : (
                        <div className="flex flex-col gap-2">
                            {runs.map((run) => (
                                <div
                                    key={run.id}
                                    className="flex items-center justify-between rounded-md border px-3 py-2 text-sm"
                                >
                                    <div className="flex items-center gap-3">
                                        <span className="font-mono text-muted-foreground">#{run.id}</span>
                                        {statusBadge(run.status)}
                                        <span className="text-muted-foreground">
                                            {new Date(run.createdAt).toLocaleString()}
                                        </span>
                                        {run.completedAt && (
                                            <span className="text-muted-foreground text-xs">
                                                — finished {new Date(run.completedAt).toLocaleString()}
                                            </span>
                                        )}
                                    </div>
                                    <div className="flex items-center gap-1">
                                        <Button
                                            variant="secondary"
                                            size="sm"
                                            disabled={isLoading || run.status === "in_progress"}
                                            onClick={() => onLoadRun(run.id)}
                                        >
                                            <Download className="h-3 w-3 mr-1" />
                                            Load
                                        </Button>
                                        <Button
                                            variant="ghost"
                                            size="sm"
                                            onClick={(e) => handleDelete(run.id, e)}
                                        >
                                            <Trash2 className="h-3 w-3 text-destructive" />
                                        </Button>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </CardContent>
            )}
        </Card>
    );
}
