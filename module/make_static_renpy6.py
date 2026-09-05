#!/usr/bin/env python
# generate_static_renpy6.py
# Generates Cython C sources for Ren'Py 6.99.12.4 and writes module/Setup for static linking.

from __future__ import print_function
import os
import sys
import re
import subprocess

BASE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(BASE)

sys.path.insert(0, BASE)

prefixes = [
    "",
    "default_",
    "hover_",
    "idle_",
    "insensitive_",
    "selected_",
    "selected_hover_",
    "selected_idle_",
    "selected_insensitive_",
    "activate_",
    "selected_activate_",
]

gen = "gen-static"
gen_dir = os.path.join(BASE, gen)
if not os.path.exists(gen_dir):
    os.makedirs(gen_dir)

cython_cmd = os.environ.get("RENPY_CYTHON", "cython")

modules = [
    ("_renpy", "module/_renpy.pyx", ["IMG_savepng.c", "core.c", "subpixel.c"]),
    ("_renpybidi", "module/_renpybidi.pyx", ["renpybidicore.c"]),
    ("renpy.audio.renpysound", "renpy/audio/renpysound.pyx", ["renpysound_core.c", "ffmedia.c"]),
    ("renpy.style", "renpy/style.pyx", []),
    ("renpy.styledata.styleclass", "renpy/styledata/styleclass.pyx", []),
    ("renpy.styledata.stylesets", "renpy/styledata/stylesets.pyx", []),
]

for p in prefixes:
    modules.append(("renpy.styledata.style_{}functions".format(p), "module/gen/style_{}functions.pyx".format(p), []))

modules.extend([
    ("renpy.display.render", "renpy/display/render.pyx", []),
    ("renpy.display.accelerator", "renpy/display/accelerator.pyx", []),
    ("renpy.gl.gl", "renpy/gl/gl.pyx", []),
    ("renpy.gl.gldraw", "renpy/gl/gldraw.pyx", ["egl_none.c"]),
    ("renpy.gl.gltexture", "renpy/gl/gltexture.pyx", []),
    ("renpy.gl.glenviron_shader", "renpy/gl/glenviron_shader.pyx", []),
    ("renpy.gl.glrtt_copy", "renpy/gl/glrtt_copy.pyx", []),
    ("renpy.gl.glrtt_fbo", "renpy/gl/glrtt_fbo.pyx", []),
    ("renpy.text.textsupport", "renpy/text/textsupport.pyx", []),
    ("renpy.text.texwrap", "renpy/text/texwrap.pyx", []),
    ("renpy.text.ftfont", "renpy/text/ftfont.pyx", ["ftsupport.c", "ttgsubtable.c"]),
])

def generate():
    setup_lines = []

    for mod_name, pyx_rel, extra_sources in modules:
        split_name = mod_name.split(".")
        c_name = mod_name + ".c"
        c_path = os.path.join(gen_dir, c_name)

        if os.path.isabs(pyx_rel):
            full_pyx = pyx_rel
        else:
            full_pyx = os.path.join(ROOT, pyx_rel)

        if not os.path.exists(full_pyx):
            print("ERROR: PYX not found:", full_pyx)
            sys.exit(1)

        print("Cythonizing:", mod_name)
        cmd = [
            cython_cmd,
            "-I", os.path.join(BASE, "include"),
            "-I", gen_dir,
            "-I", os.path.join(BASE, "gen"),
            "-I", ROOT,
            full_pyx,
            "-o", c_path
        ]
        res = subprocess.call(cmd)
        if res != 0:
            print("ERROR running Cython on", mod_name)
            sys.exit(res)

        with open(c_path, "r") as f:
            ccode = f.read()

        if len(split_name) > 1:
            parent_module = ".".join(split_name[:-1])
            parent_module_identifier = parent_module.replace(".", "_")

            ccode = re.sub(r'Py_InitModule4\("([^"]+)"', 'Py_InitModule4("' + parent_module + '.\\1"', ccode)
            ccode = re.sub(r'^__Pyx_PyMODINIT_FUNC init', '__Pyx_PyMODINIT_FUNC init' + parent_module_identifier + '_', ccode, 0, re.MULTILINE)
            ccode = re.sub(r'^PyMODINIT_FUNC init', 'PyMODINIT_FUNC init' + parent_module_identifier + '_', ccode, 0, re.MULTILINE)

            with open(c_path, "w") as f:
                f.write(ccode)

        src_parts = ["gen/" + c_name] + extra_sources
        setup_lines.append(mod_name + " " + " ".join(src_parts))

    setup_file = os.path.join(BASE, "Setup")
    with open(setup_file, "w") as f:
        f.write("# Automatically generated for Ren'Py 6.99.12.4\n")
        f.write("# Deterministic mapping for librenpy.a / _inittab\n\n")
        for line in setup_lines:
            f.write(line + "\n")

    print("Setup file written successfully:", setup_file)
    print("Total modules mapped:", len(setup_lines))

if __name__ == "__main__":
    generate()
