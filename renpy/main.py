# Copyright 2004-2017 Tom Rothamel <pytom@bishoujo.us>
#
# Permission is hereby granted, free of charge, to any person
# obtaining a copy of this software and associated documentation files
# (the "Software"), to deal in the Software without restriction,
# including without limitation the rights to use, copy, modify, merge,
# publish, distribute, sublicense, and/or sell copies of the Software,
# and to permit persons to whom the Software is furnished to do so,
# subject to the following conditions:
#
# The above copyright notice and this permission notice shall be
# included in all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
# EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
# MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
# NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
# LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
# OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
# WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

from __future__ import print_function
import renpy.display
import renpy.style
import renpy.sl2
import renpy.test

import renpy.game as game

import os
import sys
import time
import zipfile
import __main__

last_clock = time.time()


def log_clock(s):
    global last_clock
    now = time.time()
    s = "{} took {:.2f}s".format(s, now - last_clock)

    renpy.display.log.write(s)
    if renpy.android and not renpy.config.log_to_stdout:
        print(s)

    last_clock = now


def reset_clock():
    global last_clock
    last_clock = time.time()


def run(restart):
    """
    This is called during a single run of the script. Restarting the script
    will cause this to change.
    """

    reset_clock()

    # Reset the store to a clean version of itself.
    renpy.python.clean_stores()
    log_clock("Cleaning stores")

    # Init translation.
    renpy.translation.init_translation()
    log_clock("Init translation")

    # Rebuild the various style caches.
    renpy.style.build_styles()  # @UndefinedVariable
    log_clock("Build styles")

    renpy.sl2.slast.load_cache()
    log_clock("Load screen analysis")

    # Analyze the screens.
    renpy.display.screen.analyze_screens()
    log_clock("Analyze screens")

    if not restart:
        renpy.sl2.slast.save_cache()
        log_clock("Save screen analysis")

    # Prepare the screens.
    renpy.display.screen.prepare_screens()

    log_clock("Prepare screens")

    if not restart:
        renpy.pyanalysis.save_cache()
        log_clock("Save pyanalysis.")

        renpy.game.script.save_bytecode()
        log_clock("Save bytecode.")

    # Handle arguments and commands.
    if not renpy.arguments.post_init():
        renpy.exports.quit()

    if renpy.config.clear_lines:
        renpy.scriptedit.lines.clear()

    # Sleep to finish the presplash.
    renpy.display.presplash.sleep()

    # Re-Initialize the log.
    game.log = renpy.python.RollbackLog()

    # Switch contexts, begin logging.
    game.contexts = [ renpy.execution.Context(True) ]

    # Jump to an appropriate start label.
    if game.script.has_label("_start"):
        start_label = '_start'
    else:
        start_label = 'start'

    game.context().goto_label(start_label)

    # Perhaps warp.
    warp_label = renpy.warp.warp()

    if warp_label is not None:

        game.context().goto_label(warp_label)
        game.context().call('_after_warp')

        renpy.config.skipping = None

    try:
        renpy.exports.log("--- " + time.ctime())
        renpy.exports.log("")
    except:
        pass

    # Note if this is a restart.
    renpy.store._restart = restart

    # We run until we get an exception.
    renpy.display.interface.enter_context()

    log_clock("Running {}".format(start_label))

    renpy.execution.run_context(True)


def load_rpe(fn):

    zfn = zipfile.ZipFile(fn)
    autorun = zfn.read("autorun.py")
    zfn.close()

    sys.path.insert(0, fn)
    exec autorun in dict()


def choose_variants():

    if "RENPY_VARIANT" in os.environ:
        renpy.config.variants = list(os.environ["RENPY_VARIANT"].split()) + [ None ]
        return

    renpy.config.variants = [ None ]

    if renpy.android:  # @UndefinedVariable

        renpy.config.variants.insert(0, 'mobile')
        renpy.config.variants.insert(0, 'android')

        import android  # @UnresolvedImport
        import math
        import pygame_sdl2 as pygame

        # Transparent fallback wrapper for pygame.image.load to resolve via renpy.loader
        # when submods request images that reside in the APK or have casing variances.
        try:
            _orig_pygame_image_load = pygame.image.load
            def _safe_pygame_image_load(f, *args, **kwargs):
                try:
                    return _orig_pygame_image_load(f, *args, **kwargs)
                except Exception:
                    if isinstance(f, (str, unicode)):
                        rel = f.replace("\\", "/")
                        if getattr(renpy.config, "gamedir", None):
                            gdir = renpy.config.gamedir.replace("\\", "/").rstrip("/") + "/"
                            if rel.startswith(gdir):
                                rel = rel[len(gdir):]
                        if getattr(renpy.config, "basedir", None):
                            bdir = renpy.config.basedir.replace("\\", "/").rstrip("/") + "/"
                            if rel.startswith(bdir):
                                rel = rel[len(bdir):]
                        if rel.startswith("game/"):
                            rel = rel[5:]
                        try:
                            if renpy.loader.loadable(rel):
                                f_obj = renpy.loader.load(rel)
                                return _orig_pygame_image_load(f_obj, *args, **kwargs)
                        except Exception:
                            pass
                    raise
            pygame.image.load = _safe_pygame_image_load
        except Exception:
            pass

        from jnius import autoclass  # @UnresolvedImport

        # Manufacturer/Model-specific variants.
        try:
            Build = autoclass("android.os.Build")

            manufacturer = Build.MANUFACTURER
            model = Build.MODEL

            print("Manufacturer", manufacturer, "model", model)

            if manufacturer == "Amazon" and model.startswith("AFT"):
                print("Running on a Fire TV.")
                renpy.config.variants.insert(0, "firetv")
        except:
            pass

        # Are we running on OUYA or Google TV or something similar?
        package_manager = android.activity.getPackageManager()

        if package_manager.hasSystemFeature("android.hardware.type.television"):
            print("Running on a television.")
            renpy.config.variants.insert(0, "tv")
            renpy.config.variants.insert(0, "small")
            return

        # Otherwise, a phone or tablet.
        renpy.config.variants.insert(0, 'touch')

        pygame.display.init()

        info = renpy.display.get_info()
        diag = math.hypot(info.current_w, info.current_h) / android.get_dpi()
        print("Screen diagonal is", diag, "inches.")

        if diag >= 6:
            renpy.config.variants.insert(0, 'tablet')
            renpy.config.variants.insert(0, 'medium')
        else:
            renpy.config.variants.insert(0, 'phone')
            renpy.config.variants.insert(0, 'small')

    elif renpy.ios:
        renpy.config.variants.insert(0, 'ios')
        renpy.config.variants.insert(0, 'touch')

        from pyobjus import autoclass  # @UnresolvedImport @Reimport
        UIDevice = autoclass("UIDevice")

        idiom = UIDevice.currentDevice().userInterfaceIdiom

        print("iOS device idiom", idiom)

        # idiom 0 is iPhone, 1 is iPad. We assume any bigger idiom will
        # be tablet-like.
        if idiom >= 1:
            renpy.config.variants.insert(0, 'tablet')
            renpy.config.variants.insert(0, 'medium')
        else:
            renpy.config.variants.insert(0, 'phone')
            renpy.config.variants.insert(0, 'small')

    else:
        renpy.config.variants.insert(0, 'pc')
        renpy.config.variants.insert(0, 'large')


def main():

    log_clock("Bootstrap to the start of init.init")

    renpy.game.exception_info = 'Before loading the script.'

    # Get ready to accept new arguments.
    renpy.arguments.pre_init()

    # Init the screen language parser.
    renpy.sl2.slparser.init()

    # Init the config after load.
    renpy.config.init()

    # Set up variants.
    choose_variants()
    renpy.display.touch = "touch" in renpy.config.variants

    log_clock("Early init")

    # Note the game directory.
    game.basepath = renpy.config.gamedir
    renpy.config.searchpath = [ renpy.config.gamedir ]

    # Find the common directory.
    commondir = __main__.path_to_common(renpy.config.renpy_base)  # E1101 @UndefinedVariable

    if os.path.isdir(commondir):
        renpy.config.searchpath.append(commondir)
        renpy.config.commondir = commondir
    else:
        renpy.config.commondir = None

    if renpy.android:
        public_base = os.environ.get("ANDROID_PUBLIC_MOD") or os.environ.get("ANDROID_OLD_PUBLIC") or os.environ.get("ANDROID_PUBLIC", "")
        public_gamedir = os.path.join(public_base, "game") if public_base else None
        internal_gamedir = os.path.join(os.environ.get("ANDROID_PRIVATE", ""), "game")

        if public_gamedir:
            renpy.config.renpy_base = public_base
            renpy.config.basedir = public_base
            renpy.config.gamedir = public_gamedir
            game.basepath = public_gamedir
            if not os.path.exists(public_gamedir):
                try:
                    os.makedirs(public_gamedir, 0o777)
                except Exception:
                    pass

        searchpath = [ renpy.config.gamedir ]
        if internal_gamedir and os.path.isdir(internal_gamedir) and internal_gamedir != renpy.config.gamedir:
            searchpath.append(internal_gamedir)
        renpy.config.searchpath = searchpath
        renpy.config.commondir = None

        # Add parent of cachedir to searchpath so renpy.loader.load("cache/...") finds files in native Android cache
        if getattr(renpy.config, "cachedir", None):
            cache_parent = os.path.dirname(renpy.config.cachedir)
            cache_folder = os.path.basename(renpy.config.cachedir)
            if cache_folder == "cache" and cache_parent not in renpy.config.searchpath:
                renpy.config.searchpath.append(cache_parent)

        # Clean conflicting/incomplete shims from Android internal lib/python2.7 (e.g. html from future, system certifi)
        import shutil
        internal_lib = os.path.join(os.environ.get("ANDROID_PRIVATE", ""), "lib", "python2.7")
        if os.path.isdir(internal_lib):
            for ghost in ("html", "certifi"):
                ghost_path = os.path.join(internal_lib, ghost)
                if os.path.isdir(ghost_path):
                    try:
                        shutil.rmtree(ghost_path)
                    except Exception:
                        pass
        for ghost in ("html", "certifi"):
            if ghost in sys.modules:
                del sys.modules[ghost]

        # On Android, preload and protect native system ssl module before project packages can shadow it with PC dummy
        if renpy.android:
            try:
                import ssl
            except Exception:
                pass

        # Ensure Root-level lib, MAS core python-packages, and Submod libraries have priority in sys.path
        project_lib_dirs = [ ]

        # 1. Root-level lib folder (outside game/)
        if getattr(renpy.config, "basedir", None):
            root_lib = os.path.join(renpy.config.basedir, "lib")
            if os.path.isdir(root_lib):
                project_lib_dirs.append(root_lib)

        # 2. MAS core python-packages
        mas_py_pkgs = os.path.join(renpy.config.gamedir, "python-packages")
        if os.path.isdir(mas_py_pkgs):
            project_lib_dirs.append(mas_py_pkgs)
        if internal_gamedir:
            internal_py_pkgs = os.path.join(internal_gamedir, "python-packages")
            if os.path.isdir(internal_py_pkgs):
                project_lib_dirs.append(internal_py_pkgs)

        # 3. Submod bundled Python libraries
        for sm_dir_name in ("Submods", "submods"):
            sm_base = os.path.join(renpy.config.gamedir, sm_dir_name)
            if os.path.isdir(sm_base):
                try:
                    for sm in os.listdir(sm_base):
                        sm_path = os.path.join(sm_base, sm)
                        if os.path.isdir(sm_path):
                            for lib_cand in ("lib", "python-packages", "pythonlib"):
                                candidate = os.path.join(sm_path, lib_cand)
                                if os.path.isdir(candidate):
                                    project_lib_dirs.append(candidate)
                except Exception:
                    pass

        for p_lib in reversed(project_lib_dirs):
            if p_lib in sys.path:
                sys.path.remove(p_lib)
            sys.path.insert(0, p_lib)

        # Ensure native ssl is active and has SSLContext on Android
        if renpy.android:
            try:
                import ssl
                if not hasattr(ssl, "SSLContext"):
                    import imp
                    native_lib = os.path.join(os.environ.get("ANDROID_PRIVATE", ""), "lib", "python2.7")
                    f_mod, p_mod, d_mod = imp.find_module("ssl", [native_lib])
                    sys.modules["ssl"] = imp.load_module("ssl", f_mod, p_mod, d_mod)
            except Exception:
                pass

        # Provide universal _replace_charref in html module for submods (e.g. mistune in Say Something)
        try:
            import html
            if not hasattr(html, "_replace_charref"):
                _invalid_charrefs = {
                    0x00: u'\ufffd', 0x0d: u'\r', 0x80: u'\u20ac', 0x81: u'\x81', 0x82: u'\u201a',
                    0x83: u'\u0192', 0x84: u'\u201e', 0x85: u'\u2026', 0x86: u'\u2020', 0x87: u'\u2021',
                    0x88: u'\u02c6', 0x89: u'\u2030', 0x8a: u'\u0160', 0x8b: u'\u2039', 0x8c: u'\u0152',
                    0x8d: u'\x8d', 0x8e: u'\u017d', 0x8f: u'\x8f', 0x90: u'\x90', 0x91: u'\u2018',
                    0x92: u'\u2019', 0x93: u'\u201c', 0x94: u'\u201d', 0x95: u'\u2022', 0x96: u'\u2013',
                    0x97: u'\u2014', 0x98: u'\u02dc', 0x99: u'\u2122', 0x9a: u'\u0161', 0x9b: u'\u203a',
                    0x9c: u'\u0153', 0x9d: u'\x9d', 0x9e: u'\u017e', 0x9f: u'\u0178',
                }
                def _fallback_replace_charref(s):
                    val = s.group(1)
                    if val.startswith('#'):
                        try:
                            num = int(val[2:], 16) if len(val) > 1 and val[1] in 'xX' else int(val[1:])
                            if num in _invalid_charrefs:
                                return _invalid_charrefs[num]
                            if 0xD800 <= num <= 0xDFFF or num > 0x10FFFF:
                                return u'\uFFFD'
                            return unichr(num)
                        except Exception:
                            return s.group(0)
                    else:
                        try:
                            import htmlentitydefs
                            if val in htmlentitydefs.name2codepoint:
                                return unichr(htmlentitydefs.name2codepoint[val])
                        except Exception:
                            pass
                        return u'&' + val
                html._replace_charref = _fallback_replace_charref
        except Exception:
            pass



    # Load Ren'Py extensions.
    for dir in renpy.config.searchpath:  # @ReservedAssignment
        if os.path.isdir(dir):
            for fn in os.listdir(dir):
                if fn.lower().endswith(".rpe"):
                    load_rpe(dir + "/" + fn)

    # The basename is the final component of the path to the gamedir.
    if os.path.isdir(renpy.config.gamedir):
        for i in sorted(os.listdir(renpy.config.gamedir)):

            if not i.endswith(".rpa"):
                continue

            i = i[:-4]
            renpy.config.archives.append(i)

        renpy.config.archives.reverse()

    # Initialize archives.
    renpy.loader.index_archives()

    if renpy.android:
        # Guarantee physical cacert.pem on storage for native OpenSSL SSLContext
        try:
            cacert_target = os.path.join(renpy.config.gamedir, "python-packages", "certifi", "cacert.pem")
            if not os.path.exists(cacert_target):
                cacert_asset = "python-packages/certifi/cacert.pem"
                if renpy.loader.loadable(cacert_asset):
                    target_dir = os.path.dirname(cacert_target)
                    if not os.path.exists(target_dir):
                        os.makedirs(target_dir, 0o777)
                    with open(cacert_target, "wb") as f:
                        f.write(renpy.loader.load(cacert_asset).read())
        except Exception:
            pass

    # Start auto-loading.
    renpy.loader.auto_init()

    log_clock("Loader init")

    # Initialize the log.
    game.log = renpy.python.RollbackLog()

    # Initialize the store.
    renpy.store.store = sys.modules['store']

    # Set up styles.
    game.style = renpy.style.StyleManager()  # @UndefinedVariable
    renpy.store.style = game.style

    # Run init code in its own context. (Don't log.)
    game.contexts = [ renpy.execution.Context(False) ]
    game.contexts[0].init_phase = True

    renpy.execution.not_infinite_loop(60)

    # Load the script.
    renpy.game.exception_info = 'While loading the script.'
    renpy.game.script = renpy.script.Script()

    if renpy.session.get("compile", False):
        renpy.game.args.compile = True

    # Set up error handling.
    renpy.exports.load_module("_errorhandling")

    if renpy.exports.loadable("tl/None/common.rpym") or renpy.exports.loadable("tl/None/common.rpymc"):
        renpy.exports.load_module("tl/None/common")

    renpy.config.init_system_styles()
    renpy.style.build_styles()  # @UndefinedVariable

    log_clock("Loading error handling")

    # If recompiling everything, remove orphan .rpyc files.
    # Otherwise, will fail in case orphan .rpyc have same
    # labels as in other scripts (usually happens on script rename).
    if (renpy.game.args.command == 'compile') and not (renpy.game.args.keep_orphan_rpyc):  # @UndefinedVariable

        for (fn, _dir) in renpy.game.script.script_files:

            if dir is None:
                continue

            if not os.path.isfile(os.path.join(dir, fn+".rpy")):
                try:
                    name = os.path.join(dir, fn+".rpyc")
                    os.rename(name, name+".bak")
                except OSError:
                    # This perhaps shouldn't happen since either .rpy or .rpyc should exist
                    pass

        # Update script files list, so that it doesn't contain removed .rpyc's
        renpy.loader.cleardirfiles()
        renpy.game.script.scan_script_files()

    # Load all .rpy files.
    renpy.game.script.load_script()  # sets renpy.game.script.
    log_clock("Loading script")

    if renpy.game.args.command == 'load-test':  # @UndefinedVariable
        start = time.time()

        for i in range(5):
            print(i)
            renpy.game.script = renpy.script.Script()
            renpy.game.script.load_script()

        print(time.time() - start)
        sys.exit(0)

    renpy.game.exception_info = 'After loading the script.'

    # Find the save directory.
    if renpy.config.savedir is None:
        renpy.config.savedir = __main__.path_to_saves(renpy.config.gamedir)  # E1101 @UndefinedVariable

    if renpy.game.args.savedir:  # @UndefinedVariable
        renpy.config.savedir = renpy.game.args.savedir  # @UndefinedVariable

    # Init preferences.
    game.persistent = renpy.persistent.init()
    game.preferences = game.persistent._preferences

    if game.persistent._virtual_size:
        renpy.config.screen_width, renpy.config.screen_height = game.persistent._virtual_size

    # Init save locations.
    renpy.savelocation.init()

    # We need to be 100% sure we kill the savelocation thread.
    try:

        # Load persistent data from all save locations.
        renpy.persistent.update()
        game.preferences = game.persistent._preferences
        log_clock("Loading persistent")

        # Clear the list of seen statements in this game.
        game.seen_session = { }

        # Initialize persistent variables.
        renpy.store.persistent = game.persistent
        renpy.store._preferences = game.preferences
        renpy.store._test = renpy.test.testast._test

        if renpy.parser.report_parse_errors():
            raise renpy.game.ParseErrorException()

        renpy.game.exception_info = 'While executing init code:'

        for _prio, node in game.script.initcode:
            game.context().run(node)

        renpy.game.exception_info = 'After initialization, but before game start.'

        # Check if we should simulate android.
        renpy.android = renpy.android or renpy.config.simulate_android  # @UndefinedVariable

        # Run the post init code, if any.
        for i in renpy.game.post_init:
            i()

        renpy.game.script.report_duplicate_labels()

        game.persistent._virtual_size = renpy.config.screen_width, renpy.config.screen_height

        for i in renpy.game.persistent._seen_translates:  # @UndefinedVariable
            if i in renpy.game.script.translator.default_translates:
                renpy.game.seen_translates_count += 1

        log_clock("Running init code")

        renpy.pyanalysis.load_cache()
        log_clock("Loading analysis data")

        # Analyze the script and compile ATL.
        renpy.game.script.analyze()
        renpy.atl.compile_all()
        log_clock("Analyze and compile ATL")

        # Index the archive files. We should not have loaded an image
        # before this point. (As pygame will not have been initialized.)
        # We need to do this again because the list of known archives
        # may have changed.
        renpy.loader.index_archives()
        log_clock("Index archives")

        # Check some environment variables.
        renpy.game.less_memory = "RENPY_LESS_MEMORY" in os.environ
        renpy.game.less_mouse = "RENPY_LESS_MOUSE" in os.environ
        renpy.game.less_updates = "RENPY_LESS_UPDATES" in os.environ

        renpy.dump.dump(False)
        renpy.game.script.make_backups()
        log_clock("Dump and make backups.")

        # Initialize image cache.
        renpy.display.im.cache.init()
        log_clock("Cleaning cache")

        # Make a clean copy of the store.
        renpy.python.make_clean_stores()
        log_clock("Making clean stores")

        # (Perhaps) Initialize graphics.
        if not game.interface:
            renpy.display.core.Interface()
            log_clock("Creating interface object")

        # Start things running.
        restart = None

        while True:

            if restart:
                renpy.display.screen.before_restart()

            try:
                try:
                    run(restart)
                finally:
                    restart = (renpy.config.end_game_transition, "_invoke_main_menu", "_main_menu")
                    renpy.persistent.update(True)

            except game.FullRestartException, e:
                restart = e.reason

            finally:

                # Flush any pending interface work.
                renpy.display.interface.finish_pending()

                # Give Ren'Py a couple of seconds to finish saving.
                renpy.loadsave.autosave_not_running.wait(3.0)

    finally:

        renpy.loader.auto_quit()
        renpy.savelocation.quit()
        renpy.translation.write_updated_strings()

    # This is stuff we do on a normal, non-error return.
    if not renpy.display.error.error_handled:
        renpy.display.render.check_at_shutdown()
