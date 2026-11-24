#!/usr/bin/env bun
/**
 * Print section 14.1 metrics from an AgentLog-shaped JSON file.
 * Does not call a model. Does not decide whether v1 targets passed.
 *
 *   bun scripts/eval-baseline.ts [path/to/agent-log.json]
 *
 * Looks at the argument, then docs/eval/agent-log.json. When neither exists,
 * writes docs/eval/baseline.md.
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const baselinePath = join(root, "docs/eval/baseline.md");

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

function isRecord(value: Json | undefined): value is { [key: string]: Json } {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function num(value: Json | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function numList(value: Json | undefined): number[] | null {
  if (!Array.isArray(value)) return null;
  const out: number[] = [];
  for (const item of value) {
    if (typeof item !== "number" || !Number.isFinite(item)) return null;
    out.push(item);
  }
  return out;
}

function firstNum(obj: { [key: string]: Json }, keys: string[]): number | null {
  for (const key of keys) {
    const value = num(obj[key]);
    if (value != null) return value;
  }
  return null;
}

function show(value: number | string | null): string {
  if (value == null || value === "") return "not recorded";
  return String(value);
}

function fromEvents(events: Json[]): { [key: string]: Json } {
  let toolRounds = 0;
  let tokens = 0;
  let sawTokens = false;
  for (const event of events) {
    if (!isRecord(event)) continue;
    if (event.type === "assistant") {
      toolRounds += 1;
      const prompt = num(event.promptTokens);
      const completion = num(event.completionTokens);
      if (prompt != null || completion != null) {
        sawTokens = true;
        tokens += (prompt ?? 0) + (completion ?? 0);
      }
    }
  }
  const derived: { [key: string]: Json } = {};
  if (events.some((event) => isRecord(event) && event.type === "assistant")) derived.toolRounds = toolRounds;
  if (sawTokens) derived.tokens = tokens;
  return derived;
}

function loadLog(path: string): { [key: string]: Json } | null {
  const raw = JSON.parse(readFileSync(path, "utf8")) as Json;
  if (Array.isArray(raw)) return fromEvents(raw);
  if (!isRecord(raw)) return null;
  const events = Array.isArray(raw.events) ? fromEvents(raw.events) : {};
  return { ...events, ...raw };
}

const candidates = [process.argv[2], join(root, "docs/eval/agent-log.json")].filter(
  (path): path is string => !!path,
);
const found = candidates.find((path) => existsSync(path));

if (!found) {
  const text = "# Eval baseline\n\nNo model run has been recorded.\n";
  mkdirSync(dirname(baselinePath), { recursive: true });
  writeFileSync(baselinePath, text);
  process.stdout.write(text);
  process.exit(0);
}

const log = loadLog(found);
if (!log) {
  console.log(`Could not read AgentLog JSON at ${found}`);
  process.exit(1);
}

const errorsPerRound = numList(log.lintErrorsPerRound);
const lines = [
  `log: ${found}`,
  `tool rounds: ${show(firstNum(log, ["toolRounds", "rounds"]))}`,
  `rounds to first green verify: ${show(firstNum(log, ["roundsToFirstGreenVerify", "roundsToFirstGreen"]))}`,
  `total tokens: ${show(firstNum(log, ["tokens", "totalTokens"]))}`,
  `lint errors per round: ${errorsPerRound ? errorsPerRound.join(", ") : "not recorded"}`,
  `suppressions added: ${show(firstNum(log, ["suppressions", "suppressionsAdded"]))}`,
  `console errors at first preview: ${show(firstNum(log, ["consoleErrors", "runtimeConsoleErrors"]))}`,
  `csp violations at first preview: ${show(firstNum(log, ["cspViolations"]))}`,
  "v1 targets were not evaluated.",
];
console.log(lines.join("\n"));
