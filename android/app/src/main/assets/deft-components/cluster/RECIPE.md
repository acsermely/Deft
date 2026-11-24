---
id: cluster
version: 1.0.0
kind: component
license: MIT
capabilities: []
network: []
---

## Purpose

A row with a title and one action.

## API

`cluster(title, action)` returns an `html` template. `action` is another template or text.

## Behavior and states

One row. The action is whatever the caller passes.

## Accessibility

The title is an `h1` for a screen, not for a nested card.

## Theming

Uses `deft-cluster`.

## Adaptation notes

Do not add a second `h1` on the same screen.

## Acceptance checks

The title and the action are in one row.
