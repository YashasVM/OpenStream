# React + TypeScript + Vite + shadcn/ui

This is a template for a new Vite project with React, TypeScript, and shadcn/ui.

## b0 compatibility adaptation

The requested shadcn `b0` preset was translated into this existing Vite/Tauri
desktop app instead of converting it to Next.js or a monorepo. Compact b0
tokens (neutral dark surfaces, tight radii, thin borders, restrained elevation),
Base UI primitives, pointer-sized controls, and RTL-safe layout rules live in
`src/index.css` and the generated `src/components/ui/*` primitives.
`components.json` keeps the supported `base-nova` style name so future shadcn
CLI updates remain compatible, while `rtl: true` keeps generated components
RTL-ready. No new routes or media ownership are introduced by this adaptation.

## Adding components

To add components to your app, run the following command:

```bash
npx shadcn@latest add button
```

This will place the ui components in the `src/components` directory.

## Using components

To use the components in your app, import them as follows:

```tsx
import { Button } from "@/components/ui/button"
```
