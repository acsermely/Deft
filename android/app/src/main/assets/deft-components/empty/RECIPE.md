---
id: empty
version: 1.0.0
kind: component
license: MIT
capabilities: []
network: []
---

## Purpose

Show an empty screen with a title and one sentence.

## API

`empty(title, body)` returns an `html` template.

## Behavior and states

Empty is the only state. Title and body are text.

## Accessibility

The title is an `h2`. Do not use this as the page's only `h1`.

## Theming

Uses `deft-empty` and the theme tokens from `base.css`.

## Adaptation notes

Change the copy. Keep the heading level.

## Acceptance checks

The title and body are visible.
