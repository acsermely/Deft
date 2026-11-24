---
id: notice
version: 1.0.0
kind: component
license: MIT
capabilities: []
network: []
---

## Purpose

A short status line that follows the theme.

## API

`notice(text)` returns an `html` template.

## Behavior and states

One status message. No loading or error variant.

## Accessibility

`role="status"` so a screen reader can announce it.

## Theming

Uses `deft-alert`.

## Adaptation notes

Keep `role="status"`. Change the text.

## Acceptance checks

The text is visible and the element has `role="status"`.
