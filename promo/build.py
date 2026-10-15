import base64, json, pathlib
b = lambda p: base64.b64encode(pathlib.Path(p).read_bytes()).decode()
k = pathlib.Path('sfx')
s = pathlib.Path('src.html').read_text()
s = s.replace('__INTER__', b('fonts/inter.woff2')).replace('__MONO400__', b('fonts/mono400.woff2')).replace('__MONO500__', b('fonts/mono500.woff2'))
s = s.replace('__SFX__', json.dumps({n: b(k / f'{n}.ogg') for n in ['whoosh', 'click', 'pop', 'tick', 'impact', 'riser']}))
pathlib.Path('openstream-promo.html').write_text(s)
