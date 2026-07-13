# OpenStream Website

The source for [openstream.pages.dev](https://openstream.pages.dev). The site is a static React application built with Vite.

## Local development

```powershell
npm install
npm run dev
```

## Production build

```powershell
npm run build
```

Cloudflare Pages publishes the generated `dist/` directory. Before deploying, verify that the authenticated account owns the `openstream` Pages project:

```powershell
npx wrangler pages project list
npx wrangler pages deploy dist --project-name openstream --branch main
```

Do not create a second Pages project as a substitute for the production project. Release links intentionally use GitHub's `latest/download` paths so future releases do not require a website edit.
