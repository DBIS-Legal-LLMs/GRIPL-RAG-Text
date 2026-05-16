"use server"

import { EvaluationDataMeta } from "@/models/dto/EvaluationData";

export default async function getTestcasesByDataset(datasetId: number): Promise<EvaluationDataMeta[]> {
    const result = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL}/dataset/testcase`, {
        method: "GET",
        headers: { "Content-Type": "application/json" },
        cache: "no-store",
    })
        .then((data) => {
            if (!data.ok) throw new Error(`Failed to fetch testcases: ${data.statusText}`);
            return data.json() as Promise<EvaluationDataMeta[]>;
        })
        .catch((error) => {
            console.error("Error fetching testcases:", error);
            return [] as EvaluationDataMeta[];
        });

    return result.filter((tc) => tc.datasetId === datasetId);
}
