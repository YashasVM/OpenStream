import sys
src, fonts, out = sys.argv[1:4]
open(out, 'w').write(open(src).read().replace('/*FONTS*/', open(fonts).read()))
