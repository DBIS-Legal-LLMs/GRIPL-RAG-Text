"use client";

import { useCallback, useRef, useEffect, useState, useMemo } from "react";
import dynamic from "next/dynamic";
import { RagEntity, RagRelationship } from "@/models/dto/AnalysisDto";

const ForceGraph2D = dynamic(() => import("react-force-graph-2d"), { ssr: false });

interface KnowledgeGraphProps {
    entities: RagEntity[];
    relationships: RagRelationship[];
    onNodeClick?: (entity: RagEntity) => void;
}

interface GraphNode {
    id: string;
    label: string;
    type: string;
    description: string;
    connectionCount: number;
    // D3 adds these at runtime
    x?: number;
    y?: number;
    fx?: number;
    fy?: number;
}

interface GraphLink {
    source: string | GraphNode;
    target: string | GraphNode;
    label: string;
}

function resolveId(n: string | GraphNode): string {
    return typeof n === "object" ? n.id : n;
}

const TYPE_COLORS: Record<string, string> = {
    REGULATION: "#3b82f6",
    LAW: "#3b82f6",
    LEGAL_PROVISION: "#60a5fa",
    OBLIGATION: "#f97316",
    REQUIREMENT: "#f97316",
    DUTY: "#fb923c",
    PARTY: "#22c55e",
    ACTOR: "#22c55e",
    PERSON: "#4ade80",
    ORGANIZATION: "#16a34a",
    PROCESS: "#a855f7",
    ACTIVITY: "#a855f7",
    PROCEDURE: "#c084fc",
    DOCUMENT: "#eab308",
    RIGHT: "#ec4899",
    PENALTY: "#ef4444",
    DEADLINE: "#f59e0b",
};

function getNodeColor(type: string): string {
    const upper = type?.toUpperCase() ?? "";
    return TYPE_COLORS[upper] ?? "#94a3b8";
}

function getNodeRadius(connectionCount: number): number {
    return Math.max(6, Math.min(14, 6 + connectionCount * 1.5));
}

export default function KnowledgeGraph({ entities, relationships, onNodeClick }: KnowledgeGraphProps) {
    const containerRef = useRef<HTMLDivElement>(null);
    const fgRef = useRef<{ d3Force: (name: string, force: unknown) => void } | null>(null);
    const [dimensions, setDimensions] = useState({ width: 500, height: 420 });

    // Refs for hover state — used inside paintNode so the callback stays stable
    const hoveredNodeRef = useRef<GraphNode | null>(null);
    // Separate state only for the tooltip overlay (doesn't affect ForceGraph props)
    const [tooltipNode, setTooltipNode] = useState<GraphNode | null>(null);
    const [tooltipLink, setTooltipLink] = useState<GraphLink | null>(null);

    useEffect(() => {
        if (!containerRef.current) return;
        const observer = new ResizeObserver((entries) => {
            const entry = entries[0];
            setDimensions({
                width: entry.contentRect.width || 500,
                height: entry.contentRect.height || 420,
            });
        });
        observer.observe(containerRef.current);
        return () => observer.disconnect();
    }, []);

    const graphData = useMemo(() => {
        // Count how many relationships touch each entity label
        const connectionCount: Record<string, number> = {};
        for (const rel of relationships) {
            connectionCount[rel.source] = (connectionCount[rel.source] ?? 0) + 1;
            connectionCount[rel.target] = (connectionCount[rel.target] ?? 0) + 1;
        }

        // Deduplicate entities by label
        const seen = new Set<string>();
        const nodes: GraphNode[] = [];
        for (const e of entities) {
            if (!seen.has(e.label)) {
                seen.add(e.label);
                nodes.push({
                    id: e.label,
                    label: e.label,
                    type: e.type,
                    description: e.description,
                    connectionCount: connectionCount[e.label] ?? 0,
                });
            }
        }

        // Only include links where both endpoints exist as nodes
        const nodeIds = new Set(nodes.map((n) => n.id));
        const links: GraphLink[] = relationships
            .filter((r) => nodeIds.has(r.source) && nodeIds.has(r.target))
            .map((r) => ({ source: r.source, target: r.target, label: r.label }));

        return { nodes, links };
    }, [entities, relationships]);

    // Pin a node only after the user drags it, so it stays where they put it
    // but the rest of the graph remains free to react to the force simulation.
    const handleNodeDragEnd = useCallback((node: GraphNode) => {
        node.fx = node.x;
        node.fy = node.y;
    }, []);

    // paintNode has NO dependency on hover state — it reads the ref instead.
    // This keeps the function reference stable across hover events so
    // ForceGraph2D never sees a changed prop and never restarts the simulation.
    const paintNode = useCallback(
        (node: GraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
            const r = getNodeRadius(node.connectionCount);
            const x = node.x ?? 0;
            const y = node.y ?? 0;

            // Circle fill
            ctx.beginPath();
            ctx.arc(x, y, r, 0, 2 * Math.PI);
            ctx.fillStyle = getNodeColor(node.type);
            ctx.fill();

            // Hover ring — read from ref, not state
            if (hoveredNodeRef.current?.id === node.id) {
                ctx.strokeStyle = "#ffffff";
                ctx.lineWidth = 2 / globalScale;
                ctx.stroke();
            }

            // Label — only when zoomed in enough
            if (globalScale > 0.6) {
                const fontSize = Math.max(3, 10 / globalScale);
                ctx.font = `${fontSize}px sans-serif`;
                ctx.textAlign = "center";
                ctx.textBaseline = "middle";
                ctx.fillStyle = "#ffffff";

                const maxWidth = r * 2.5;
                let text = node.label;
                if (ctx.measureText(text).width > maxWidth) {
                    while (text.length > 3 && ctx.measureText(text + "…").width > maxWidth) {
                        text = text.slice(0, -1);
                    }
                    text = text + "…";
                }
                ctx.fillText(text, x, y);
            }
        },
        [] // stable — no dependencies
    );

    if (graphData.nodes.length === 0) {
        return (
            <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
                No entities to display.
            </div>
        );
    }

    return (
        <div ref={containerRef} className="relative w-full" style={{ height: "420px" }}>
            <ForceGraph2D
                ref={fgRef as never}
                width={dimensions.width}
                height={dimensions.height}
                graphData={graphData}
                nodeCanvasObject={paintNode as never}
                nodeCanvasObjectMode={() => "replace"}
                nodeVal={(node) => getNodeRadius((node as GraphNode).connectionCount)}
                nodeLabel="" // disable built-in tooltip — we handle it ourselves
                linkLabel={(link) => (link as GraphLink).label}
                linkColor={() => "#475569"}
                linkWidth={1.2}
                linkDirectionalArrowLength={4}
                linkDirectionalArrowRelPos={1}
                onNodeDragEnd={handleNodeDragEnd as never}
                onNodeClick={(node) => onNodeClick?.(node as unknown as RagEntity)}
                onNodeHover={(node) => {
                    const n = node as GraphNode | null;
                    hoveredNodeRef.current = n;   // for canvas ring — no re-render
                    setTooltipNode(n);            // for tooltip overlay — re-render ok here
                    setTooltipLink(null);
                }}
                onLinkHover={(link) => {
                    hoveredNodeRef.current = null;
                    setTooltipNode(null);
                    if (!link) { setTooltipLink(null); return; }
                    const l = link as GraphLink;
                    setTooltipLink({ source: resolveId(l.source), target: resolveId(l.target), label: l.label });
                }}
                backgroundColor="transparent"
                cooldownTicks={150}
                d3AlphaDecay={0.03}
                d3VelocityDecay={0.4}
                nodeRelSize={1}
            />

            {/* Node tooltip */}
            {tooltipNode && (
                <div className="absolute bottom-2 left-2 right-2 bg-background/95 border border-border rounded-md p-2 text-xs pointer-events-none shadow-md">
                    <span className="font-semibold">{tooltipNode.label}</span>
                    <span className="ml-1 text-muted-foreground">[{tooltipNode.type}]</span>
                    {tooltipNode.description && (
                        <p className="mt-0.5 text-muted-foreground line-clamp-3">{tooltipNode.description}</p>
                    )}
                </div>
            )}

            {/* Link tooltip */}
            {tooltipLink && !tooltipNode && (
                <div className="absolute bottom-2 left-2 bg-background/95 border border-border rounded-md px-2 py-1 text-xs pointer-events-none shadow-md">
                    <span className="font-medium">{resolveId(tooltipLink.source)}</span>
                    <span className="text-muted-foreground mx-1">→</span>
                    <span className="font-medium">{resolveId(tooltipLink.target)}</span>
                    <span className="text-muted-foreground ml-1">({tooltipLink.label})</span>
                </div>
            )}

            {/* Legend */}
            <div className="absolute top-2 right-2 bg-background/80 border border-border rounded-md p-1.5 text-xs space-y-0.5">
                {Object.entries({
                    "Regulation / Law": "#3b82f6",
                    "Obligation": "#f97316",
                    "Party / Actor": "#22c55e",
                    "Process": "#a855f7",
                    "Other": "#94a3b8",
                }).map(([label, color]) => (
                    <div key={label} className="flex items-center gap-1.5">
                        <span className="inline-block w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: color }} />
                        <span className="text-muted-foreground">{label}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}
