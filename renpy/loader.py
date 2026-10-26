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
import renpy
import os.path
from pickle import loads
from cStringIO import StringIO
import sys
import types
import threading
import time
import zlib
import re

# Ensure the utf-8 codec is loaded, to prevent recursion when we use it
# to look up filenames.
u"".encode("utf-8")


# Physical Paths

def get_path(fn):
    """
    Returns the path to `fn` relative to the gamedir. If any of the directories
    leading to `fn` do not exist, tries to create them.

    This always returns a path, but the path may or may not be writable.
    """

    if fn.startswith("cache/") and getattr(renpy.config, "cachedir", None):
        fn = os.path.join(renpy.config.cachedir, fn[6:])
    else:
        fn = os.path.join(renpy.config.gamedir, fn)

    dn = os.path.dirname(fn)

    try:
        if not os.path.exists(dn):
            os.makedirs(dn)
    except:
        pass

    return fn

# Asset Loading

class _LazyOffsets(dict):
    """
    Data offset of each apk entry, computed the first time the entry is opened
    instead of reading every entry header at startup.
    """

    def __init__(self, apk_path, info):
        dict.__init__(self)
        self.apk_path = apk_path
        self.info = info

    def __missing__(self, fn):
        import struct
        import zipfile

        info = self.info[fn]

        with open(self.apk_path, "rb") as f:
            f.seek(info.header_offset)
            h = struct.unpack(zipfile.structFileHeader, f.read(zipfile.sizeFileHeader))

        rv = (
            info.header_offset +
            zipfile.sizeFileHeader +
            h[zipfile._FH_FILENAME_LENGTH] +
            h[zipfile._FH_EXTRA_FIELD_LENGTH])

        self[fn] = rv
        return rv


def _open_apks(apk_path, prefixes):
    """
    Same as creating one android.apk.APK per prefix, but the zip index is read
    only once and shared, and the entry offsets are computed lazily.
    """

    import zipfile

    zf = zipfile.ZipFile(apk_path, "r")
    infolist = zf.infolist()

    rv = [ ]

    for prefix in prefixes:
        apk = android.apk.APK.__new__(android.apk.APK)
        apk.apk = apk_path
        apk.zf = zf
        apk.info = { }

        for i in infolist:
            if i.filename.startswith(prefix):
                apk.info[i.filename[len(prefix):]] = i

        apk.offset = _LazyOffsets(apk_path, apk.info)
        rv.append(apk)

    return rv


try:
    import android.apk

    expansion = os.environ.get("ANDROID_EXPANSION", None)
    if expansion is not None:
        print("Using expansion file", expansion)
        apk_path = expansion
    else:
        print("Not using expansion file.")
        apk_path = None

    try:
        apks = _open_apks(
            apk_path or os.environ["ANDROID_APK"],
            [ 'assets/x-game/', 'assets/x-renpy/x-common/' ])

    except Exception as e:
        print("Fast apk index failed, using the default one:", repr(e))

        apks = [
            android.apk.APK(apk=apk_path, prefix='assets/x-game/'),
            android.apk.APK(apk=apk_path, prefix='assets/x-renpy/x-common/'),
            ]

    game_apks = [ apks[0] ]

except ImportError:
    apks = [ ]
    game_apks = [ ]


# Archives (.rpa) shipped inside the apk, as (apk path, offset of the archive
# in the apk, index). They're checked after the loose apk files and before
# the archives on disk.
apk_archives = [ ]


def _load_rpa_index(f):
    """
    Reads the index of an RPA-3.0 archive from the open file `f`.
    """

    l = f.readline()

    if not l.startswith("RPA-3.0 "):
        raise Exception("Only RPA-3.0 archives are supported in the apk.")

    offset = int(l[8:24], 16)
    key = int(l[25:33], 16)
    f.seek(offset)
    index = loads(zlib.decompress(f.read()))

    for k in index.keys():
        if len(index[k][0]) == 2:
            index[k] = [ (offset ^ key, dlen ^ key) for offset, dlen in index[k] ]
        else:
            index[k] = [ (offset ^ key, dlen ^ key, start) for offset, dlen, start in index[k] ]

    return index


def _index_apk_archives():
    import zipfile

    for apk in game_apks:
        for fn in sorted(apk.info):

            # Only archives at the top of the game directory
            if "/" in fn or not fn.endswith(".rpa"):
                continue

            if apk.info[fn].compress_type != zipfile.ZIP_STORED:
                print("Skipping compressed archive in the apk (add rpa to noCompress):", fn)
                continue

            try:
                f = apk.open(fn)
                index = _load_rpa_index(f)
                f.close()

                apk_archives.append((apk.apk, apk.offset[fn], index))

            except Exception as e:
                print("Could not index archive in the apk", fn, repr(e))


if game_apks:
    _index_apk_archives()

# Files on disk should be checked before archives. Otherwise, among
# other things, using a new version of bytecode.rpyb will break.
archives = [ ]

# The value of renpy.config.archives the last time index_archives was
# run.
old_config_archives = None

# A map from lower-case filename to regular-case filename.
lower_map = { }


def index_archives():
    """
    Loads in the indexes for the archive files. Also updates the lower_map.
    """

    # Index the archives.

    global old_config_archives

    if old_config_archives == renpy.config.archives:
        return

    old_config_archives = renpy.config.archives[:]

    # Update lower_map.
    lower_map.clear()

    cleardirfiles()

    global archives
    archives = [ ]

    for prefix in renpy.config.archives:

        try:
            fn = transfn(prefix + ".rpa")
            f = file(fn, "rb")
            l = f.readline()

            # 3.0 Branch.
            if l.startswith("RPA-3.0 "):
                offset = int(l[8:24], 16)
                key = int(l[25:33], 16)
                f.seek(offset)
                index = loads(f.read().decode("zlib"))

                # Deobfuscate the index.

                for k in index.keys():

                    if len(index[k][0]) == 2:
                        index[k] = [ (offset ^ key, dlen ^ key) for offset, dlen in index[k] ]
                    else:
                        index[k] = [ (offset ^ key, dlen ^ key, start) for offset, dlen, start in index[k] ]

                archives.append((prefix, index))

                f.close()
                continue

            # 2.0 Branch.
            if l.startswith("RPA-2.0 "):
                offset = int(l[8:], 16)
                f.seek(offset)
                index = loads(f.read().decode("zlib"))
                archives.append((prefix, index))
                f.close()
                continue

            # 1.0 Branch.
            f.close()

            fn = transfn(prefix + ".rpi")
            index = loads(file(fn, "rb").read().decode("zlib"))
            archives.append((prefix, index))
        except:
            raise

    for dir, fn in listdirfiles():  # @ReservedAssignment
        lower_map[fn.lower()] = fn


def walkdir(dir):  # @ReservedAssignment
    rv = [ ]

    if not os.path.exists(dir) and not renpy.config.developer:
        return rv

    for i in os.listdir(dir):
        if i[0] == ".":
            continue

        if os.path.isdir(dir + "/" + i):
            for fn in walkdir(dir + "/" + i):
                rv.append(i + "/" + fn)
        else:
            rv.append(i)

    return rv


# A list of files that make up the game.
game_files = [ ]

# A list of files that are in the common directory.
common_files = [ ]


def cleardirfiles():
    """
    Clears the lists above when the game has changed.
    """

    global game_files
    global common_files

    game_files = [ ]
    common_files = [ ]


def scandirfiles():
    """
    Scans directories, archives, and apks and fills out game_files and
    common_files.
    """

    seen = { }

    def add(dn, fn):
        if fn.startswith("cache/"):
            return

        if fn.startswith("saves/"):
            return

        if fn in seen:
            old_files, idx, old_dn = seen[fn]
            if old_dn is None and dn is not None:
                # Direct filesystem path overrides APK / archive entry
                old_files[idx] = (dn, fn)
                seen[fn] = (old_files, idx, dn)
            return

        idx = len(files)
        files.append((dn, fn))
        seen[fn] = (files, idx, dn)

    for apk in apks:

        if apk not in game_apks:
            files = common_files  # @UnusedVariable
        else:
            files = game_files  # @UnusedVariable

        for f in apk.list():

            # Strip off the "x-" in front of each filename, which is there
            # to ensure that aapt actually includes every file.
            f = "/".join(i[2:] for i in f.split("/"))

            add(None, f)

    files = game_files

    for _apk_path, _base, index in apk_archives:
        for j in index.iterkeys():
            add(None, j)

    for i in renpy.config.searchpath:

        if (renpy.config.commondir) and (i == renpy.config.commondir):
            files = common_files  # @UnusedVariable
        else:
            files = game_files  # @UnusedVariable

        i = os.path.join(renpy.config.basedir, i)
        for j in walkdir(i):
            add(i, j)

    files = game_files

    for _prefix, index in archives:
        for j in index.iterkeys():
            add(None, j)


def listdirfiles(common=True):
    """
    Returns a list of directory, file tuples known to the system. If
    the file is in an archive, the directory is None.
    """

    if (not game_files) and (not common_files):
        scandirfiles()

    if common:
        return game_files + common_files
    else:
        return list(game_files)


class SubFile(object):

    def __init__(self, fn, base, length, start):
        self.fn = fn

        self.f = None

        self.base = base
        self.offset = 0
        self.length = length
        self.start = start

        if not self.start:
            self.name = fn
        else:
            self.name = None

    def open(self):
        self.f = open(self.fn, "rb")
        self.f.seek(self.base)

    def __enter__(self):
        return self

    def __exit__(self, _type, value, tb):
        self.close()
        return False

    def read(self, length=None):

        if self.f is None:
            self.open()

        maxlength = self.length - self.offset

        if length is not None:
            length = min(length, maxlength)
        else:
            length = maxlength

        rv1 = self.start[self.offset:self.offset + length]
        length -= len(rv1)
        self.offset += len(rv1)

        if length:
            rv2 = self.f.read(length)
            self.offset += len(rv2)
        else:
            rv2 = ""

        return (rv1 + rv2)

    def readline(self, length=None):

        if self.f is None:
            self.open()

        maxlength = self.length - self.offset
        if length is not None:
            length = min(length, maxlength)
        else:
            length = maxlength

        # If we're in the start, then read the line ourselves.
        if self.offset < len(self.start):
            rv = ''

            while length:
                c = self.read(1)
                rv += c
                if c == '\n':
                    break
                length -= 1

            return rv

        # Otherwise, let the system read the line all at once.
        rv = self.f.readline(length)

        self.offset += len(rv)

        return rv

    def readlines(self, length=None):
        rv = [ ]

        while True:
            l = self.readline(length)

            if not l:
                break

            if length is not None:
                length -= len(l)
                if l < 0:
                    break

            rv.append(l)

        return rv

    def xreadlines(self):
        return self

    def __iter__(self):
        return self

    def next(self):  # @ReservedAssignment
        rv = self.readline()

        if not rv:
            raise StopIteration()

        return rv

    def flush(self):
        return

    def seek(self, offset, whence=0):

        if self.f is None:
            self.open()

        if whence == 0:
            offset = offset
        elif whence == 1:
            offset = self.offset + offset
        elif whence == 2:
            offset = self.length + offset

        if offset > self.length:
            offset = self.length

        self.offset = offset

        offset = offset - len(self.start)
        if offset < 0:
            offset = 0

        self.f.seek(offset + self.base)

    def tell(self):
        return self.offset

    def close(self):
        if self.f is not None:
            self.f.close()
            self.f = None

    def write(self, s):
        raise Exception("Write not supported by SubFile")

open_file = open

if "RENPY_FORCE_SUBFILE" in os.environ:
    def open_file(name, mode):
        f = open(name, mode)

        f.seek(0, 2)
        length = f.tell()
        f.seek(0, 0)

        return SubFile(f, 0, length, '')


def load_core(name):
    """
    Returns an open python file object of the given type.
    """

    name = lower_map.get(name.lower(), name)

    if renpy.config.file_open_callback:
        rv = renpy.config.file_open_callback(name)
        if rv is not None:
            return rv

    # Look for the file directly on the filesystem first.
    if not renpy.config.force_archives:
        try:
            fn = transfn(name)
            return open_file(fn, "rb")
        except:
            pass

    # Look for the file in the apk.
    for apk in apks:
        prefixed_name = "/".join("x-" + i for i in name.split("/"))

        try:
            return apk.open(prefixed_name)
        except IOError:
            pass

    # Look for it in the archives inside the apk.
    for apk_path, base, index in apk_archives:
        if name not in index:
            continue

        if len(index[name]) == 1:
            t = index[name][0]
            if len(t) == 2:
                offset, dlen = t
                start = ''
            else:
                offset, dlen, start = t

            return SubFile(apk_path, base + offset, dlen, start)

        with open(apk_path, "rb") as f:
            data = [ ]

            for t in index[name]:
                f.seek(base + t[0])
                data.append(f.read(t[1]))

        return StringIO(''.join(data))

    # Look for it in archive files.
    for prefix, index in archives:
        if not name in index:
            continue

        afn = transfn(prefix + ".rpa")

        data = [ ]

        # Direct path.
        if len(index[name]) == 1:

            t = index[name][0]
            if len(t) == 2:
                offset, dlen = t
                start = ''
            else:
                offset, dlen, start = t

            rv = SubFile(afn, offset, dlen, start)

        # Compatibility path.
        else:
            f = file(afn, "rb")

            for offset, dlen in index[name]:
                f.seek(offset)
                data.append(f.read(dlen))

            rv = StringIO(''.join(data))
            f.close()

        return rv

    return None


def get_prefixes():
    """
    Returns a list of prefixes to search for files.
    """

    rv = [ ]

    language = renpy.game.preferences.language

    for prefix in renpy.config.search_prefixes:

        if language is not None:
            rv.append(renpy.config.tl_directory + "/" + language + "/" + prefix)

        rv.append(prefix)

    return rv


def load(name):

    if renpy.config.reject_backslash and "\\" in name:
        raise Exception("Backslash in filename, use '/' instead: %r" % name)

    name = re.sub(r'/+', '/', name).lstrip('/')

    for p in get_prefixes():
        rv = load_core(p + name)
        if rv is not None:
            return rv

    raise IOError("Couldn't find file '%s'." % name)


loadable_cache = { }


def loadable_core(name):
    """
    Returns True if the name is loadable with load, False if it is not.
    """

    name = lower_map.get(name.lower(), name)

    if name in loadable_cache:
        return loadable_cache[name]

    for apk in apks:
        prefixed_name = "/".join("x-" + i for i in name.split("/"))
        if prefixed_name in apk.info:
            return True

    for _apk_path, _base, index in apk_archives:
        if name in index:
            loadable_cache[name] = True
            return True

    try:
        transfn(name)
        loadable_cache[name] = True
        return True
    except:
        pass

    for _prefix, index in archives:
        if name in index:
            loadable_cache[name] = True
            return True

    loadable_cache[name] = False
    return False


def loadable(name):

    name = name.lstrip('/')

    for p in get_prefixes():
        if loadable_core(p + name):
            return True

    return False


def transfn(name):
    """
    Tries to translate the name to a file that exists in one of the
    searched directories.
    """

    name = name.lstrip('/')

    if renpy.config.reject_backslash and "\\" in name:
        raise Exception("Backslash in filename, use '/' instead: %r" % name)

    name = lower_map.get(name.lower(), name)

    if isinstance(name, str):
        name = name.decode("utf-8")

    for d in renpy.config.searchpath:
        fn = os.path.join(renpy.config.basedir, d, name)

        add_auto(fn)

        if os.path.exists(fn):
            return fn

    raise Exception("Couldn't find file '%s'." % name)


hash_cache = dict()


def get_hash(name):
    """
    Returns the time the file m was last modified, or 0 if it
    doesn't exist or is archived.
    """

    rv = hash_cache.get(name, None)
    if rv is not None:
        return rv

    rv = 0

    try:
        f = load(name)

        while True:
            data = f.read(1024 * 1024)

            if not data:
                break

            rv = zlib.adler32(data, rv)

    except:
        pass

    hash_cache[name] = rv

    return rv


# Module Loading

class RenpyImporter(object):
    """
    An importer, that tries to load modules from the places where Ren'Py
    searches for data files.
    """

    def __init__(self, prefix=""):
        self.prefix = prefix

    def translate(self, fullname, prefix=None):

        if prefix is None:
            prefix = self.prefix

        try:
            fn = (prefix + fullname.replace(".", "/")).decode("utf8")
        except:
            # raise Exception("Could importer-translate %r + %r" % (prefix, fullname))
            return None

        if loadable(fn + ".py"):
            return fn + ".py"

        if loadable(fn + "/__init__.py"):
            return fn + "/__init__.py"

        return None

    def find_module(self, fullname, path=None):
        if path is not None:
            for i in path:
                if self.translate(fullname, i):
                    return RenpyImporter(i)

        if self.translate(fullname):
            return self

    def load_module(self, fullname):

        filename = self.translate(fullname, self.prefix)

        mod = sys.modules.setdefault(fullname, types.ModuleType(fullname))
        mod.__name__ = fullname
        mod.__file__ = filename
        mod.__loader__ = self

        if filename.endswith("__init__.py"):
            mod.__path__ = [ filename[:-len("__init__.py")] ]

        source = load(filename).read().decode("utf8")
        if source and source[0] == u'\ufeff':
            source = source[1:]
        source = source.encode("raw_unicode_escape")

        source = source.replace("\r", "")

        code = compile(source, filename, 'exec', renpy.python.old_compile_flags, 1)
        exec code in mod.__dict__

        return mod

    def get_data(self, filename):
        return load(filename).read()


def init_importer():
    sys.meta_path.insert(0, RenpyImporter("python-packages/"))
    sys.meta_path.insert(0, RenpyImporter())


def quit_importer():
    sys.meta_path.pop(0)
    sys.meta_path.pop(0)

# Auto-Reload

# This is set to True if autoreload hads detected an autoreload is needed.
needs_autoreload = False

# A map from filename to mtime, or None if the file doesn't exist.
auto_mtimes = { }

# The thread used for autoreload.
auto_thread = None

# True if auto_thread should run. False if it should quit.
auto_quit_flag = True

# The lock used by auto_thread.
auto_lock = threading.Condition()

# Used to indicate that this file is blacklisted.
auto_blacklisted = renpy.object.Sentinel("auto_blacklisted")


def auto_mtime(fn):
    """
    Gets the mtime of fn, or None if the file does not exist.
    """

    try:
        return os.path.getmtime(fn)
    except:
        return None


def add_auto(fn, force=False):
    """
    Adds fn as a file we watch for changes. If it's mtime changes or the file
    starts/stops existing, we trigger a reload.
    """

    if not renpy.autoreload:
        return

    if (fn in auto_mtimes) and (not force):
        return

    for e in renpy.config.autoreload_blacklist:
        if fn.endswith(e):
            with auto_lock:
                auto_mtimes[fn] = auto_blacklisted
            return

    mtime = auto_mtime(fn)

    with auto_lock:
        auto_mtimes[fn] = mtime


def auto_thread_function():
    """
    This thread sets need_autoreload when necessary.
    """

    global needs_autoreload

    while True:

        with auto_lock:

            auto_lock.wait(1.5)

            if auto_quit_flag:
                return

            items = auto_mtimes.items()

        for fn, mtime in items:

            if mtime is auto_blacklisted:
                continue

            if auto_mtime(fn) != mtime:

                with auto_lock:
                    if auto_mtime(fn) != auto_mtimes[fn]:
                        needs_autoreload = True


def auto_init():
    """
    Starts the autoreload thread.
    """

    global auto_thread
    global auto_quit_flag
    global needs_autoreload

    needs_autoreload = False

    if not renpy.autoreload:
        return

    auto_quit_flag = False

    auto_thread = threading.Thread(target=auto_thread_function)
    auto_thread.daemon = True
    auto_thread.start()


def auto_quit():
    """
    Terminates the autoreload thread.
    """
    global auto_quit_flag

    if auto_thread is None:
        return

    auto_quit_flag = True

    with auto_lock:
        auto_lock.notify_all()

    auto_thread.join()


# Join the apk and the public game folder for the python code of the game ##################################

# The loader above already reads both, but only for what goes through it (images, scripts, sounds...).
# The python code of a game or a submod lists and opens files with os.listdir, os.path.exists and open,
# which only see real files, so whatever is inside the apk stays hidden from it. Here those functions also
# look at the apk when the path is inside the game folder:
#
# * os.listdir adds the names that are in the apk.
# * os.path.exists, isdir and isfile say yes for what is in the apk.
# * open and io.open (read only) read a file from the apk when it is not on disk.
#
# A file on disk always wins over the one in the apk, writing is never touched, and only the python code of
# the game is affected: the calls made by the engine itself (the renpy package) and by the standard library
# go straight to the real functions. The index of the apk is built the first time it is needed, from the
# apk index above, so it costs nothing at startup.

import io
import __builtin__

_fs_orig_listdir = os.listdir
_fs_orig_exists = os.path.exists
_fs_orig_isdir = os.path.isdir
_fs_orig_isfile = os.path.isfile
_fs_orig_open = __builtin__.open
_fs_orig_io_open = io.open

_fs_installed = False
_fs_index = None  # (files, dirs), built the first time it is needed
_fs_apk = None    # object with .info and .open(name), the game part of the apk
_fs_roots = []    # real game folders, as they are written by the game


def _fs_decode(s):
    if isinstance(s, unicode):
        return s
    try:
        return s.decode("utf-8")
    except Exception:
        return s.decode("latin-1")


def _fs_strip(component):
    # Every name in the apk starts with "x-", so aapt includes every file.
    if component.startswith("x-"):
        return component[2:]
    return component


def _fs_build_index(apk):
    """
    Returns (files, dirs) of an apk object: `files` maps the path inside the game folder to the name
    in the apk, `dirs` maps each folder to the set of names it holds.
    """

    files = {}
    dirs = {u"": set()}

    for key in apk.info:
        parts = [_fs_strip(c) for c in _fs_decode(key).split(u"/")]

        if not parts[-1]:
            continue

        files[u"/".join(parts)] = key

        for i in range(len(parts)):
            dirs.setdefault(u"/".join(parts[:i]), set()).add(parts[i])

    return files, dirs


def _fs_get_index():
    global _fs_index
    if _fs_index is None:
        _fs_index = _fs_build_index(_fs_apk)
    return _fs_index


def _fs_from_game_code():
    """
    True when the function was called from the python code of the game (the store), not by the engine
    or the standard library.
    """

    try:
        name = sys._getframe(2).f_globals.get("__name__", "")
    except Exception:
        return False

    return name == "store" or name.startswith("store.")


def _fs_relative(path):
    """
    The path inside the game folder, or None if `path` isn't in it.
    """

    if not isinstance(path, (str, unicode)):
        return None

    for root in _fs_roots:
        try:
            matches = path.startswith(root)
        except UnicodeError:
            return None

        if matches:
            rest = path[len(root):]
            if rest and rest[0] not in ("/", "\\"):
                continue

            rest = rest.replace("\\", "/")
            parts = [p for p in rest.split("/") if p and p != "."]

            if ".." in parts:
                return None

            return _fs_decode(u"/".join(parts))

    return None


def _fs_listdir(path="."):
    rel = _fs_relative(path)
    if rel is not None and not _fs_from_game_code():
        rel = None

    if rel is None:
        return _fs_orig_listdir(path)

    dirs = _fs_get_index()[1]
    names = dirs.get(rel)

    try:
        real = _fs_orig_listdir(path)
    except OSError:
        if names is None:
            raise
        return list(names)

    if not names:
        return real

    seen = set(real)
    return real + [n for n in names if n not in seen]


def _fs_exists(path):
    if _fs_orig_exists(path):
        return True

    rel = _fs_relative(path)
    if rel is not None and not _fs_from_game_code():
        rel = None

    if rel is None:
        return False

    files, dirs = _fs_get_index()
    return rel in files or rel in dirs


def _fs_isdir(path):
    if _fs_orig_isdir(path):
        return True

    rel = _fs_relative(path)
    if rel is not None and not _fs_from_game_code():
        rel = None

    if rel is None:
        return False

    return rel in _fs_get_index()[1]


def _fs_isfile(path):
    if _fs_orig_isfile(path):
        return True

    rel = _fs_relative(path)
    if rel is not None and not _fs_from_game_code():
        rel = None

    if rel is None:
        return False

    return rel in _fs_get_index()[0]


def _fs_read_only(mode):
    return not any(c in mode for c in "wa+x")


def _fs_apk_data(path):
    """
    The bytes of a file that is only in the apk, or None.
    """

    rel = _fs_relative(path)
    if rel is None:
        return None

    key = _fs_get_index()[0].get(rel)
    if key is None:
        return None

    f = _fs_apk.open(key)
    try:
        return f.read()
    finally:
        f.close()


def _fs_open(name, mode="r", *args, **kwargs):
    if _fs_read_only(mode) and _fs_relative(name) is not None and _fs_from_game_code() and not _fs_orig_exists(name):
        data = _fs_apk_data(name)
        if data is not None:
            return io.BytesIO(data)

    return _fs_orig_open(name, mode, *args, **kwargs)


def _fs_io_open(file, mode="r", *args, **kwargs):  # @ReservedAssignment
    if isinstance(file, (str, unicode)) and _fs_read_only(mode) and _fs_relative(file) is not None and _fs_from_game_code() and not _fs_orig_exists(file):
        data = _fs_apk_data(file)
        if data is not None:
            raw = io.BytesIO(data)
            if "b" in mode:
                return raw

            encoding = kwargs.get("encoding") or (args[1] if len(args) > 1 else None) or "utf-8"
            errors = kwargs.get("errors") or (args[2] if len(args) > 2 else None)
            return io.TextIOWrapper(raw, encoding=encoding, errors=errors)

    return _fs_orig_io_open(file, mode, *args, **kwargs)


def join_apk_and_public_game_folders(roots):
    """
    Replaces the file functions so the python code of the game also sees the game folder of the apk.
    `roots` are the real paths of the game folder, as the game writes them.
    """

    global _fs_installed, _fs_apk, _fs_roots, _fs_index

    if not game_apks:
        return

    _fs_apk = game_apks[0]
    _fs_index = None
    _fs_roots = [ r for r in roots if r ]

    if _fs_installed:
        return

    os.listdir = _fs_listdir
    os.path.exists = _fs_exists
    os.path.isdir = _fs_isdir
    os.path.isfile = _fs_isfile
    __builtin__.open = _fs_open
    io.open = _fs_io_open
    _fs_installed = True
