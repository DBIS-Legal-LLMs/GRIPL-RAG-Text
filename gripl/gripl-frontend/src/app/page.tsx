"use client"

import BpmnEditor from "@/components/bpmn-editor";
import {useState} from "react";
import {BpmnToolCard} from "@/models/BpmnToolCard";
import AnalysisToolCard from "@/components/sandbox/analysis-tool-card";
import emptyDiagram from "@/data/empty-diagram.bpmn";
import {AnalysisResponse} from "@/models/dto/AnalysisDto";
import AnalysisResultCard from "@/components/sandbox/analysis-result-card";
import RagContextCard from "@/components/sandbox/rag-context-card";
import {BpmnEditorEvent} from "@/models/BpmnEditorEvent";
import {Tabs, TabsContent, TabsList, TabsTrigger} from "@/components/ui/tabs";
import {Card, CardContent} from "@/components/ui/card";
import {Brain, BookOpen} from "lucide-react";

export default function Home() {
  const [diagram, setDiagram] = useState<string>(emptyDiagram as string)
  const [analysisResult, setAnalysisResult] = useState<AnalysisResponse | null>(null)
  const [selectedElementId, setSelectedElementId] = useState<string | null>(null)

  function handleCreateNewDiagram() {
    setAnalysisResult(null)
  }

  function onEvent(type: BpmnEditorEvent, event: any) {
    if (type === BpmnEditorEvent.SelectionChanged) {
      setSelectedElementId(event.newSelection?.length === 1 ? event.newSelection[0].id : null)
    }
  }

  const hasRagContext = analysisResult?.ragContext && Object.keys(analysisResult.ragContext).length > 0;

  const bottomPanel = analysisResult ? (
      hasRagContext ? (
          <Card className="container">
              <Tabs defaultValue="reasoning">
                  <div className="px-4 pt-3">
                      <TabsList>
                          <TabsTrigger value="reasoning" className="gap-1.5">
                              <Brain className="h-3.5 w-3.5" />
                              AI Reasoning
                          </TabsTrigger>
                          <TabsTrigger value="rag-context" className="gap-1.5">
                              <BookOpen className="h-3.5 w-3.5" />
                              Retrieved Knowledge
                          </TabsTrigger>
                      </TabsList>
                  </div>
                  <CardContent className="pt-2">
                      <TabsContent value="reasoning">
                          <table className="w-full">
                              <thead>
                              <tr className="bg-muted">
                                  <th className="text-left text-sm font-semibold p-2">Activity</th>
                                  <th className="text-left text-sm font-semibold p-2">Reasoning</th>
                              </tr>
                              </thead>
                              <tbody>
                              {analysisResult.criticalElements.map((element, index) => {
                                  const isSelected = element.id === selectedElementId
                                  const hasRefs = element.references && element.references.length > 0
                                  return <tr key={index} className={`border-t align-top ${isSelected ? "bg-destructive/50" : ""}`}>
                                      <td className="font-medium text-sm p-2 whitespace-nowrap">{element.name}</td>
                                      <td className="text-sm p-2">
                                          <p>{element.reason || "No reasoning provided"}</p>
                                          {hasRefs && (
                                              <details className="mt-2">
                                                  <summary className="cursor-pointer text-xs font-semibold text-primary/80 hover:text-primary select-none w-fit">
                                                      📎 References ({element.references!.length})
                                                  </summary>
                                                  <div className="mt-1.5 space-y-2 pl-1">
                                                      {element.references!.map((ref, ri) => (
                                                          <div key={ri} className="border-l-2 border-primary/30 pl-2 space-y-0.5">
                                                              <blockquote className="text-xs text-muted-foreground italic">
                                                                  &ldquo;{ref.exactText}&rdquo;
                                                              </blockquote>
                                                              {ref.sourceDocument && (
                                                                  <p className="text-xs text-primary/70 font-medium">
                                                                      📄 {ref.sourceDocument.replace(/[_-]/g, " ")}
                                                                  </p>
                                                              )}
                                                          </div>
                                                      ))}
                                                  </div>
                                              </details>
                                          )}
                                      </td>
                                  </tr>
                              })}
                              </tbody>
                          </table>
                      </TabsContent>
                      <TabsContent value="rag-context">
                          <RagContextCard
                              ragContext={analysisResult.ragContext!}
                              selectedElementId={selectedElementId}
                          />
                      </TabsContent>
                  </CardContent>
              </Tabs>
          </Card>
      ) : (
          <AnalysisResultCard analysisResult={analysisResult} selectedElementId={selectedElementId} />
      )
  ) : null;

  const editorToolCards: BpmnToolCard[] = [
    {
      position: "top-right",
      content: <AnalysisToolCard
          bpmnXml={diagram}
          analysisResult={analysisResult}
          setAnalysisResult={setAnalysisResult}
      />
    } as BpmnToolCard,
    ...(bottomPanel ? [{
      position: "bottom-center" as const,
      content: bottomPanel
    }] : [])
  ]

  return <main className="flex flex-col justify-center items-center h-full w-full">
    <div className="w-full h-full">
      <BpmnEditor
          bpmnXml={diagram}
          highlightedActivityIds={analysisResult?.criticalElements?.map(e => e.id) || []}
          onNew={handleCreateNewDiagram}
          onDiagramChanged={setDiagram}
          cards={editorToolCards}
          onEvent={onEvent}
      />
    </div>
  </main>
}