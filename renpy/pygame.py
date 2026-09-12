from __future__ import absolute_import

try:
    import pygame_sdl2
    import sys
    sys.modules['renpy.pygame'] = pygame_sdl2
    from pygame_sdl2 import *
    try:
        _orig_set_blocked = pygame_sdl2.event.set_blocked
        def _safe_set_blocked(events):
            try:
                if isinstance(events, (int, long)):
                    events = [events]
                return _orig_set_blocked(events)
            except TypeError:
                try:
                    return _orig_set_blocked([events])
                except Exception:
                    pass
            except Exception:
                pass
        pygame_sdl2.event.set_blocked = _safe_set_blocked
        event.set_blocked = _safe_set_blocked
    except Exception:
        pass
except ImportError:
    import pygame
    import sys
    sys.modules['renpy.pygame'] = pygame
    from pygame import *
