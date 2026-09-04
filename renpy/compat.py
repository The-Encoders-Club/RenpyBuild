# Compatibility module for Ren'Py 6 Python 2.7 runtime
from __future__ import absolute_import, division, print_function

import sys
import io

PY2 = (sys.version_info[0] == 2)

if PY2:
    basestring = basestring
    pystr = str
    unicode = unicode
    str = str
    open = open
    range = xrange
    chr = chr
    bchr = chr
    bord = ord
    round = round
    def tobytes(s):
        if isinstance(s, unicode):
            return s.encode('latin-1')
        return bytes(s)
else:
    import builtins
    basestring = (str,)
    pystr = str
    unicode = str
    str = builtins.str
    open = builtins.open
    range = range
    chr = chr
    def bchr(i):
        return bytes([i])
    def bord(s):
        return s[0]
    round = round
    def tobytes(s):
        if isinstance(s, str):
            return s.encode('latin-1')
        return bytes(s)

__all__ = ['PY2', 'basestring', 'bchr', 'bord', 'chr', 'open', 'pystr', 'range', 'str', 'tobytes', 'unicode', 'round']
