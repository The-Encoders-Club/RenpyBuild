from __future__ import absolute_import

try:
    import pygame_sdl2
    import sys
    sys.modules['renpy.pygame'] = pygame_sdl2
    from pygame_sdl2 import *
except ImportError:
    import pygame
    import sys
    sys.modules['renpy.pygame'] = pygame
    from pygame import *
