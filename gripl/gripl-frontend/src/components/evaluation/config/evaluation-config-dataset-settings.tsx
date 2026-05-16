"use client";

import { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Dataset } from "@/models/dto/Dataset";
import { EvaluationDataMeta } from "@/models/dto/EvaluationData";
import getTestcasesByDataset from "@/actions/get-testcases-by-dataset";

interface EvaluationConfigDatasetSettingsProps {
    datasets: Dataset[];
    selectedDatasets: number[];
    selectedTestCaseIds: number[];
    onDatasetsChange: (ids: number[]) => void;
    onTestCasesChange: (ids: number[]) => void;
}

export default function EvaluationConfigDatasetSettings({
    datasets,
    selectedDatasets,
    selectedTestCaseIds,
    onDatasetsChange,
    onTestCasesChange,
}: EvaluationConfigDatasetSettingsProps) {
    const [activeDatasetId, setActiveDatasetId] = useState<number | null>(null);
    const [testCases, setTestCases] = useState<EvaluationDataMeta[]>([]);
    const [loadingTestCases, setLoadingTestCases] = useState(false);

    useEffect(() => {
        if (activeDatasetId === null) return;
        setLoadingTestCases(true);
        getTestcasesByDataset(activeDatasetId).then((tcs) => {
            setTestCases(tcs);
            // default: all selected
            onTestCasesChange(tcs.map((tc) => tc.id));
            setLoadingTestCases(false);
        });
    }, [activeDatasetId]);

    function handleDatasetClick(datasetId: number) {
        if (activeDatasetId === datasetId) return;
        setActiveDatasetId(datasetId);
        onDatasetsChange([datasetId]);
        setTestCases([]);
        onTestCasesChange([]);
    }

    function toggleTestCase(id: number) {
        if (selectedTestCaseIds.includes(id)) {
            onTestCasesChange(selectedTestCaseIds.filter((x) => x !== id));
        } else {
            onTestCasesChange([...selectedTestCaseIds, id]);
        }
    }

    const allSelected = testCases.length > 0 && testCases.every((tc) => selectedTestCaseIds.includes(tc.id));

    function toggleAll() {
        if (allSelected) {
            onTestCasesChange([]);
        } else {
            onTestCasesChange(testCases.map((tc) => tc.id));
        }
    }

    return (
        <Card>
            <CardHeader>
                <CardTitle className="text-lg">Datasets</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
                <div className="space-y-2">
                    <Label>Select Dataset</Label>
                    <div className="flex flex-wrap gap-2">
                        {datasets.map((ds) => (
                            <button
                                key={ds.id}
                                onClick={() => handleDatasetClick(ds.id)}
                                className={`px-3 py-1.5 rounded-md text-sm border transition-colors ${
                                    activeDatasetId === ds.id
                                        ? "bg-primary text-primary-foreground border-primary"
                                        : "bg-muted text-muted-foreground border-border hover:bg-accent hover:text-accent-foreground"
                                }`}
                            >
                                {ds.name}
                            </button>
                        ))}
                    </div>
                </div>

                {activeDatasetId !== null && (
                    <div className="space-y-2">
                        <div className="flex items-center justify-between">
                            <Label>
                                Test Cases
                                {testCases.length > 0 && (
                                    <span className="ml-2 text-muted-foreground font-normal">
                                        ({selectedTestCaseIds.length}/{testCases.length} selected)
                                    </span>
                                )}
                            </Label>
                            {testCases.length > 0 && (
                                <Button variant="ghost" size="sm" onClick={toggleAll}>
                                    {allSelected ? "Deselect All" : "Select All"}
                                </Button>
                            )}
                        </div>

                        {loadingTestCases ? (
                            <p className="text-sm text-muted-foreground">Loading...</p>
                        ) : testCases.length === 0 ? (
                            <p className="text-sm text-muted-foreground">No test cases found.</p>
                        ) : (
                            <div className="max-h-56 overflow-y-auto space-y-1 rounded-md border border-border p-2">
                                {testCases.map((tc) => (
                                    <div
                                        key={tc.id}
                                        className="flex items-center gap-2 px-2 py-1 rounded hover:bg-accent cursor-pointer"
                                        onClick={() => toggleTestCase(tc.id)}
                                    >
                                        <input
                                            type="checkbox"
                                            checked={selectedTestCaseIds.includes(tc.id)}
                                            onChange={() => toggleTestCase(tc.id)}
                                            className="h-4 w-4 rounded border-border accent-primary"
                                            onClick={(e) => e.stopPropagation()}
                                        />
                                        <span className="text-sm truncate">{tc.name ?? `Test Case ${tc.id}`}</span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                )}
            </CardContent>
        </Card>
    );
}
