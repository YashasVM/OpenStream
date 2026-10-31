export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== '/irl' && !url.pathname.startsWith('/irl/')) {
      return new Response('Not found', { status: 404 });
    }
    if (!['GET', 'HEAD'].includes(request.method)) {
      return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });
    }
    const path = url.pathname.slice(4);
    if (!['', '/', '/index.html', '/style.css', '/app.js', '/status.json'].includes(path)) {
      return new Response('Not found', { status: 404 });
    }
    url.pathname = path === '' || path === '/' ? '/index.html' : path;
    const asset = await env.ASSETS.fetch(new Request(url, request));
    const response = new Response(asset.body, asset);
    response.headers.set('Cache-Control', 'no-cache');
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'no-referrer');
    response.headers.set('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
    return response;
  },
};
