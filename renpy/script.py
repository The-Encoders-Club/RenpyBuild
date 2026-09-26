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

# This file contains code that is responsible for storing and executing a
# Ren'Py script.

from __future__ import print_function
import renpy

import hashlib
import os
import imp
import difflib
import time
import marshal
import struct
import zlib

from cPickle import loads, dumps
import shutil

# The version of the dumped script.
script_version = renpy.script_version

# The version of the bytecode cache.
BYTECODE_VERSION = 1

# The python magic code.
MAGIC = imp.get_magic()

# A string at the start of each rpycv2 file.
RPYC2_HEADER = "RENPY RPC2"

# A string
BYTECODE_FILE = "cache/bytecode.rpyb"


class ScriptError(Exception):
    """
    Exception that is raised if the script is somehow inconsistent,
    or otherwise wrong.
    """


def collapse_stmts(stmts):
    """
    Returns a flat list containing every statement in the tree
    stmts.
    """

    rv = [ ]

    for i in stmts:
        i.get_children(rv.append)

    return rv


def get_cpu_count():
    try:
        if hasattr(os, "sysconf"):
            if "SC_NPROCESSORS_ONLN" in os.sysconf_names:
                n = os.sysconf("SC_NPROCESSORS_ONLN")
                if isinstance(n, (int, long)) and n > 0:
                    return n
    except:
        pass

    try:
        with open("/sys/devices/system/cpu/online", "r") as f:
            line = f.read().strip()
            if "-" in line:
                parts = line.split("-")
                return int(parts[1]) - int(parts[0]) + 1
    except:
        pass

    try:
        with open("/proc/cpuinfo", "r") as f:
            count = sum(1 for line in f if line.startswith("processor"))
            if count > 0:
                return count
    except:
        pass

    try:
        n = os.environ.get("NUMBER_OF_PROCESSORS")
        if n:
            return int(n)
    except:
        pass

    return 4


class Script(object):
    """
    This class represents a Ren'Py script, which is parsed out of a
    collection of script files. Once parsing and initial analysis is
    complete, this object can be serialized out and loaded back in,
    so it shouldn't change at all after that has happened.

    @ivar namemap: A map from the name of an AST node to the AST node
    itself.  This is used for jumps, calls, and to find the current
    node when loading back in a save. The names may be strings or
    integers, strings being explicit names provided by the user, and
    integers being names synthesised by renpy.

    @ivar initcode: A list of priority, Node tuples that should be
    executed in ascending priority order at init time.

    @ivar all_stmts: A list of all statements, that have been found
    in every file. Useful for lint, but tossed if lint is not performed
    to save memory.

    """

    def __init__(self):
        """
        Loads the script by parsing all of the given files, and then
        walking the various ASTs to initialize this Script object.
        """

        # Set us up as renpy.game.script, so things can use us while
        # we're loading.
        renpy.game.script = self

        if os.path.exists(renpy.config.renpy_base + "/lock.txt"):
            self.key = file(renpy.config.renpy_base + "/lock.txt", "rb").read()
        else:
            self.key = None

        self.namemap = { }
        self.all_stmts = [ ]
        self.all_pycode = [ ]

        # A list of statements that haven't been analyzed.
        self.need_analysis = [ ]

        self.record_pycode = True

        # Bytecode caches.
        self.bytecode_oldcache = { }
        self.bytecode_newcache = { }
        self.bytecode_dirty = False

        self.translator = renpy.translation.ScriptTranslator()

        self.init_bytecode()
        self.scan_script_files()

        self.translator.chain_translates()

        self.serial = 0

        self.digest = hashlib.md5(renpy.version_only)

        self.loaded_rpy = False
        self.backup_list = [ ]

        self.duplicate_labels = [ ]

    def choose_backupdir(self):

        if renpy.mobile:
            return None

        for i in [ "script_version.txt", "script_version.rpy", "script_version.rpyc" ]:
            if renpy.loader.loadable(i):
                return None

        import __main__
        backups = __main__.path_to_saves(renpy.config.gamedir, "backups")  # @UndefinedVariable

        if backups is None:
            return

        basename = os.path.basename(renpy.config.basedir)
        backupdir = renpy.os.path.join(renpy.exports.fsencode(backups),
                                       renpy.exports.fsencode(basename))

        renpy.exports.write_log("Backing up script files to %r:", backupdir)

        return backupdir

    def make_backups(self):

        backup_list = self.backup_list
        self.backup_list = [ ]

        if os.environ.get("RENPY_DISABLE_BACKUPS", "") == "I take responsibility for this.":
            return

        if not self.loaded_rpy:
            return

        if renpy.mobile:
            return

        backupdir = self.choose_backupdir()
        if backupdir is None:
            return

        for fn, checksum in backup_list:

            if not fn.startswith(renpy.config.gamedir):
                continue

            if not os.path.exists(fn):
                continue

            short_fn = renpy.exports.fsencode(fn[len(renpy.config.gamedir)+1:])

            base, ext = os.path.splitext(short_fn)
            target_fn = os.path.join(
                backupdir,
                base + "." + checksum[:8].encode("hex") + ext,
                )

            if os.path.exists(target_fn):
                continue

            try:
                os.makedirs(os.path.dirname(target_fn), 0o700)
            except:
                pass

            try:
                shutil.copy(fn, target_fn)
            except:
                pass

    def scan_script_files(self):
        """
        Scan the directories for script files.
        """

        # A list of all files in the search directories.
        dirlist = renpy.loader.listdirfiles()

        # A list of directory, filename w/o extension pairs. This is
        # what we will load immediately.
        self.script_files = [ ]

        # Similar, but for modules:
        self.module_files = [ ]

        for dir, fn in dirlist:  # @ReservedAssignment

            if fn.endswith(".rpy"):
                if dir is None:
                    continue

                fn = fn[:-4]
                target = self.script_files
            elif fn.endswith(".rpyc"):
                fn = fn[:-5]
                target = self.script_files
            elif fn.endswith(".rpym"):
                if dir is None:
                    continue

                fn = fn[:-5]
                target = self.module_files
            elif fn.endswith(".rpymc"):
                fn = fn[:-6]
                target = self.module_files
            else:
                continue

            if (fn, dir) not in target:
                target.append((fn, dir))

    def load_monolithic_script(self, initcode, game_scripts):
        """
        Attempts to load game scripts from a unified monolithic script bundle (script.rpyb).
        Common core scripts (00*.rpyc) are loaded beforehand to ensure all namespaces and stores are ready.
        Returns True if the bundle was loaded and initialized successfully, False otherwise.
        """
        if getattr(renpy.game.args, "compile", False):
            return False

        bundle_file = None
        for candidate in ("script.rpyb", "cache/script.rpyb"):
            try:
                if renpy.loader.loadable(candidate):
                    bundle_file = renpy.loader.load(candidate)
                    break
            except Exception:
                pass

        if bundle_file is None:
            return False

        t0 = time.time()

        # Save snapshot of state in case of rollback
        old_namemap = dict(self.namemap)
        old_initcode_len = len(initcode)
        old_digest = self.digest.copy() if hasattr(self.digest, 'copy') else None

        try:
            raw_data = bundle_file.read()
            bundle_file.close()

            t_decomp_start = time.time()
            uncompressed = zlib.decompress(raw_data)
            t_decomp = time.time() - t_decomp_start

            t_bun_load_start = time.time()
            bundle = loads(uncompressed)
            t_bun_load = time.time() - t_bun_load_start

            if not isinstance(bundle, dict) or "files" not in bundle:
                return False

            # Preload all 7,124 precompiled Python bytecodes into cache
            t_bc_inject = 0.0
            if "bytecode_raw" in bundle and bundle["bytecode_raw"]:
                try:
                    t_bc_start = time.time()
                    ver, bcache = loads(bundle["bytecode_raw"].decode("zlib"))
                    if ver == BYTECODE_VERSION:
                        self.bytecode_oldcache = bcache
                        t_bc_inject = time.time() - t_bc_start
                except Exception:
                    pass

            records = bundle["files"]
            loaded_files = set()
            success_count = 0

            t_loads_sum = 0.0
            t_finish_sum = 0.0

            for fn, bindata, digest, slot in records:
                try:
                    t_l0 = time.time()
                    data, stmts = loads(bindata)
                    t_loads_sum += (time.time() - t_l0)

                    if not data or not isinstance(data, dict):
                        continue

                    if data.get('version') != script_version:
                            raise ValueError("Version mismatch")

                    if self.key and data.get('key', 'unlocked') != self.key:
                        continue

                    if self.key is None:
                        self.key = data.get('key')

                    if slot < 2:
                        self.static_transforms(stmts)

                    source = ".rpy"
                    t_f0 = time.time()
                    self.finish_load(stmts, initcode, filename=fn + source)
                    t_finish_sum += (time.time() - t_f0)

                    if digest:
                        self.digest.update(digest)

                    loaded_files.add(fn)
                    success_count += 1
                except Exception as e:
                    import traceback
                    renpy.exports.write_log("Monolithic error loading %s: %s\n%s", fn, str(e), traceback.format_exc())
                    raise


            # Check for any dynamic / external scripts not in the bundle (e.g. Submods)
            remaining_scripts = [ (fn, dir) for (fn, dir) in game_scripts if fn not in loaded_files ]
            if remaining_scripts:
                for fn, dir in remaining_scripts:
                    self.load_appropriate_file(".rpyc", ".rpy", dir, fn, initcode)

            return True

        except Exception as e:
            import traceback
            renpy.exports.write_log("Monolithic script load failed: %s\n%s. Rolling back state.", str(e), traceback.format_exc())
            # Clean rollback
            self.namemap = old_namemap
            del initcode[old_initcode_len:]
            if old_digest:
                self.digest = old_digest
            return False

    def load_script(self):

        script_files = self.script_files

        # Sort script files by filename.
        script_files.sort()

        initcode = [ ]

        # 1. Load core Ren'Py common scripts first (00*.rpyc) to initialize core stores, ATL, namespaces
        common_scripts = [ (fn, dir) for (fn, dir) in script_files if fn.startswith("00") ]
        game_scripts = [ (fn, dir) for (fn, dir) in script_files if not fn.startswith("00") ]

        for fn, dir in common_scripts:
            self.load_appropriate_file(".rpyc", ".rpy", dir, fn, initcode)

        # 2. Load game scripts via ultra-fast monolithic bundle (<0.8s target)
        if self.load_monolithic_script(initcode, game_scripts):
            pass

        # Multithreaded decompression pipeline on Android fallback for game scripts.
        elif renpy.android:
            import threading

            t_pipeline_start = time.time()
            num_threads = max(2, get_cpu_count())
            total_files = len(game_scripts)

            renpy.exports.write_log("=== MULTITHREAD PIPELINE START ===")
            renpy.exports.write_log("Cores detected: %d, Total game script files: %d", num_threads, total_files)

            results = [ None ] * total_files
            events = [ threading.Event() for _ in range(total_files) ]

            work_queue = list(enumerate(game_scripts))
            work_queue.reverse()
            queue_lock = threading.Lock()
            load_lock = threading.Lock()

            def worker():
                while True:
                    queue_lock.acquire()
                    if not work_queue:
                        queue_lock.release()
                        break
                    idx, (fn, dir) = work_queue.pop()
                    queue_lock.release()

                    res = None
                    try:
                        f = None
                        if dir is None:
                            load_lock.acquire()
                            try:
                                f = renpy.loader.load(fn + ".rpyc")
                            finally:
                                load_lock.release()
                        else:
                            rpyfn = dir + "/" + fn + ".rpy"
                            rpycfn = dir + "/" + fn + ".rpyc"
                            if os.path.exists(rpyfn):
                                if not os.path.exists(rpycfn) or renpy.game.args.compile:
                                    f = None
                                else:
                                    with open(rpyfn, "rU") as rpyf:
                                        rpydigest = hashlib.md5(rpyf.read()).digest()
                                    with open(rpycfn, "rb") as cf:
                                        cf.seek(-hashlib.md5().digest_size, 2)
                                        rpycdigest = cf.read(hashlib.md5().digest_size)
                                    if rpydigest == rpycdigest:
                                        f = open(rpycfn, "rb")
                                    else:
                                        f = None
                            elif os.path.exists(rpycfn):
                                f = open(rpycfn, "rb")

                        if f is not None:
                            try:
                                f.seek(-hashlib.md5().digest_size, 2)
                                digest = f.read(hashlib.md5().digest_size)
                                f.seek(0)
                            except:
                                digest = None

                            for slot in (2, 1):
                                try:
                                    bindata = self.read_rpyc_data(f, slot)
                                    if bindata:
                                        res = (bindata, digest, slot)
                                        break
                                except:
                                    pass
                                f.seek(0)
                            f.close()
                    except Exception as e:
                        import traceback
                        res = ("__ERROR__", str(e), traceback.format_exc(), None)

                    results[idx] = res
                    events[idx].set()

            threads = [ ]
            for _ in range(num_threads):
                t = threading.Thread(target=worker)
                t.daemon = True
                t.start()
                threads.append(t)

            t_wait_sum = 0.0
            t_unpickle_sum = 0.0
            t_finish_sum = 0.0
            success_count = 0
            fallback_count = 0

            for idx, (fn, dir) in enumerate(game_scripts):
                t0 = time.time()
                events[idx].wait()
                t1 = time.time()
                t_wait_sum += (t1 - t0)

                res = results[idx]
                results[idx] = None

                loaded = False
                fallback_reason = None

                if res is not None and len(res) == 4 and res[0] == "__ERROR__":
                    fallback_reason = "Worker Error: %s" % res[1]
                elif res is not None:
                    try:
                        bindata, digest, slot = res
                        tu0 = time.time()
                        data, stmts = loads(bindata)
                        tu1 = time.time()
                        t_unpickle_sum += (tu1 - tu0)

                        if not data or not isinstance(data, dict):
                            fallback_reason = "Data not dict"
                        elif data.get('version') != script_version:
                            fallback_reason = "Version mismatch (got %s, expected %s)" % (data.get('version'), script_version)
                        elif self.key and data.get('key', 'unlocked') != self.key:
                            fallback_reason = "Key mismatch"
                        else:
                            if self.key is None:
                                self.key = data['key']

                            if slot < 2:
                                self.static_transforms(stmts)

                            tf0 = time.time()
                            source = ".rpy"
                            self.finish_load(stmts, initcode, filename=fn + source)
                            if digest:
                                self.digest.update(digest)

                            if dir is not None:
                                rpyfn = dir + "/" + fn + source
                                self.backup_list.append((rpyfn, digest))

                            tf1 = time.time()
                            t_finish_sum += (tf1 - tf0)

                            loaded = True
                    except Exception as e:
                        fallback_reason = "Main Thread Exception: %s" % str(e)
                else:
                    fallback_reason = "res is None"

                if loaded:
                    success_count += 1
                else:
                    fallback_count += 1
                    renpy.exports.write_log("MULTITHREAD FALLBACK on %s: %s", fn, fallback_reason)
                    self.load_appropriate_file(".rpyc", ".rpy", dir, fn, initcode)

            for t in threads:
                t.join()

            t_total = time.time() - t_pipeline_start
            renpy.exports.write_log("MULTITHREAD SUMMARY: total=%.3fs | wait=%.3fs | unpickle=%.3fs | finish_load=%.3fs | success=%d | fallback=%d",
                                    t_total, t_wait_sum, t_unpickle_sum, t_finish_sum, success_count, fallback_count)
            renpy.exports.write_log("=== MULTITHREAD PIPELINE END ===")

        else:
            for fn, dir in game_scripts:
                self.load_appropriate_file(".rpyc", ".rpy", dir, fn, initcode)

        # Make the sort stable.
        initcode = [ (prio, index, code) for index, (prio, code) in
                     enumerate(initcode) ]

        initcode.sort()

        self.initcode = [ (prio, code) for prio, index, code in initcode ]

    def load_module(self, name):

        files = [ (fn, dir) for fn, dir in self.module_files if fn == name ]  # @ReservedAssignment

        if not files:
            raise Exception("Module %s could not be loaded." % name)

        if len(files) > 2:
            raise Exception("Module %s ambiguous, multiple variants exist." % name)

        fn, dir = files[0]  # @ReservedAssignment
        initcode = [ ]

        self.load_appropriate_file(".rpymc", ".rpym", dir, fn, initcode)

        if renpy.parser.report_parse_errors():
            raise SystemExit(-1)

        self.translator.chain_translates()

        return initcode

    def assign_names(self, stmts, fn):
        # Assign names to statements that don't have one already.

        all_stmts = collapse_stmts(stmts)

        version = int(time.time())

        for s in all_stmts:
            if s.name is None:
                s.name = (fn, version, self.serial)
                self.serial += 1

    def merge_names(self, old_stmts, new_stmts):

        old_stmts = collapse_stmts(old_stmts)
        new_stmts = collapse_stmts(new_stmts)

        old_info = [ i.diff_info() for i in old_stmts ]
        new_info = [ i.diff_info() for i in new_stmts ]

        sm = difflib.SequenceMatcher(None, old_info, new_info)

        for oldl, newl, count in sm.get_matching_blocks():
            for i in range(count):
                old = old_stmts[oldl + i]
                new = new_stmts[newl + i]

                if new.name is None:
                    new.name = old.name

    def load_string(self, filename, filedata, linenumber=1):
        """
        Loads Ren'Py script from a string.

        `filename`
            The filename that's assigned to the data.

        `filedata`
            A unicode string to be loaded.

        Return the list of statements making up the root block, and a
        list of init statements that need to be run.
        """

        stmts = renpy.parser.parse(filename, filedata, linenumber=linenumber)

        if stmts is None:
            return None, None

        self.assign_names(stmts, filename)
        self.static_transforms(stmts)

        initcode = [ ]

        stmts = self.finish_load(stmts, initcode, False)

        return stmts, initcode

    def finish_load(self, stmts, initcode, check_names=True, filename=None):
        """
        Given `stmts`, a list of AST nodes comprising the root block,
        finishes loading it.

        `initcode`
            A list we append init statements to.

        `check_names`
            If true, produce duplicate name errors.

        `filename`
            If given, a filename that overrides the filename found inside the
            file.

        Returns a list of statements that corresponds to the top-level block
        in initcode after transformation.
        """

        if not stmts:
            return stmts

        # Chain together the statements in the file.
        renpy.ast.chain_block(stmts, None)

        # All of the statements found in file, regardless of nesting
        # depth.

        all_stmts = [ ]
        for i in stmts:
            i.get_children(all_stmts.append)

        # Take the translations.
        self.translator.take_translates(all_stmts)

        # Fix the filename for a renamed .rpyc file.
        if filename is not None:
            filename = renpy.parser.elide_filename(filename)

            if not all_stmts[0].filename.lower().endswith(filename.lower()):
                filename += "c"
                for i in all_stmts:
                    i.filename = filename

        # Check for duplicate names.
        if check_names and not renpy.mobile:
            bad_name = None
            bad_node = None
            old_node = None

            for node in all_stmts:
                name = node.name

                if name in self.namemap:

                    bad_name = name
                    bad_node = node
                    old_node = self.namemap[name]

                    if not isinstance(bad_name, basestring):

                        raise ScriptError("Name %s is defined twice, at %s:%d and %s:%d." %
                                          (repr(bad_name),
                                           old_node.filename, old_node.linenumber,
                                           bad_node.filename, bad_node.linenumber))

                    else:
                        self.duplicate_labels.append(
                            u'The label {} is defined twice, at\n  File "{}", line {} and\n  File "{}", line {}.'.format(
                                bad_name, old_node.filename, old_node.linenumber, bad_node.filename, bad_node.linenumber))

                # Add twice, so we can find duplicates in the same file.
                self.namemap[name] = node

        self.update_bytecode()

        for node in all_stmts:

            name = node.name

            # Add the name to the namemap.
            self.namemap[name] = node

            # Add any init nodes to self.initcode.
            if node.get_init:
                init = node.get_init()
                if init:
                    initcode.append(init)

            if node.early_execute:
                node.early_execute()

        if self.all_stmts is not None:
            self.all_stmts.extend(all_stmts)

        self.need_analysis.extend(all_stmts)

        return stmts

    def write_rpyc_header(self, f):
        """
        Writes an empty version 2 .rpyc header to the open binary file `f`.
        """

        f.write(RPYC2_HEADER)

        for _i in range(3):
            f.write(struct.pack("III", 0, 0, 0))

    def write_rpyc_data(self, f, slot, data):
        """
        Writes data into `slot` of a .rpyc file. The data should be a binary
        string, and is compressed before being written.
        """

        f.seek(0, 2)

        start = f.tell()
        data = zlib.compress(data, 9)
        f.write(data)

        f.seek(len(RPYC2_HEADER) + 12 * (slot - 1), 0)
        f.write(struct.pack("III", slot, start, len(data)))

        f.seek(0, 2)

    def write_rpyc_md5(self, f, digest):
        """
        Writes the md5 to the end of a .rpyc file.
        """

        f.seek(0, 2)
        f.write(digest)

    def read_rpyc_data(self, f, slot):
        """
        Reads the binary data from `slot` in a .rpyc (v1 or v2) file. Returns
        the data if the slot exists, or None if the slot does not exist.
        """

        # f.seek(0)
        header_data = f.read(1024)

        # header = f.read(len(RPYC2_HEADER))

        # Legacy path.
        if header_data[:len(RPYC2_HEADER)] != RPYC2_HEADER:
            if slot != 1:
                return None

            f.seek(0)
            data = f.read()

            return data.decode("zlib")

        # RPYC2 path.
        pos = len(RPYC2_HEADER)

        while True:
            header_slot, start, length = struct.unpack("III", header_data[pos:pos+12])

            if slot == header_slot:
                break

            if header_slot == 0:
                return None

            pos += 12

        f.seek(start)
        data = f.read(length)

        return zlib.decompress(data)

    def static_transforms(self, stmts):
        """
        This performs transformations on the script that can be performed
        statically. When possible, these transforms are stored in slot 2
        of the rpyc file.
        """

        # Generate translate nodes.
        renpy.translation.restructure(stmts)

    def load_file(self, dir, fn):  # @ReservedAssignment

        if fn.endswith(".rpy") or fn.endswith(".rpym"):

            if not dir:
                raise Exception("Cannot load rpy/rpym file %s from inside an archive." % fn)

            fullfn = dir + "/" + fn
            rpycfn = fullfn + "c"

            stmts = renpy.parser.parse(fullfn)

            data = { }
            data['version'] = script_version
            data['key'] = self.key or 'unlocked'

            if stmts is None:
                return data, [ ]

            # See if we have a corresponding .rpyc file. If so, then
            # we want to try to upgrade our .rpy file with it.
            try:
                self.record_pycode = False

                with open(rpycfn, "rb") as rpycf:
                    bindata = self.read_rpyc_data(rpycf, 1)

                old_data, old_stmts = loads(bindata)

                self.merge_names(old_stmts, stmts)

                del old_data
                del old_stmts
            except:
                pass
            finally:
                self.record_pycode = True

            self.assign_names(stmts, fullfn)

            if not renpy.macapp:

                try:
                    f = file(rpycfn, "wb")

                    self.write_rpyc_header(f)
                    self.write_rpyc_data(f, 1, dumps((data, stmts), 2))
                except:
                    pass

            self.static_transforms(stmts)

            if not renpy.macapp:

                try:
                    self.write_rpyc_data(f, 2, dumps((data, stmts), 2))

                    with open(fullfn, "rU") as fullf:
                        rpydigest = hashlib.md5(fullf.read()).digest()

                    self.write_rpyc_md5(f, rpydigest)

                    f.close()
                except:
                    pass

            self.loaded_rpy = True

        elif fn.endswith(".rpyc") or fn.endswith(".rpymc"):

            data = None
            stmts = None

            if dir is not None:
                f = open(dir + "/" + fn, "rb")
            else:
                f = renpy.loader.load(fn)

            try:
                try:
                    f.seek(-hashlib.md5().digest_size, 2)
                    self._last_digest = f.read(hashlib.md5().digest_size)
                    f.seek(0)
                except:
                    self._last_digest = None

                for slot in [ 2, 1 ]:
                    try:
                        bindata = self.read_rpyc_data(f, slot)

                        if bindata:
                            data, stmts = loads(bindata)
                            break

                    except:
                        pass

                    f.seek(0)

                else:
                    return None, None

                if data is None:
                    print("Failed to load", fn)
                    return None, None

                if not isinstance(data, dict):
                    return None, None

                if self.key and data.get('key', 'unlocked') != self.key:
                    return None, None

                if data['version'] != script_version:
                    return None, None

                if slot < 2:
                    self.static_transforms(stmts)

            finally:
                f.close()

        else:
            return None, None

        return data, stmts

    def load_appropriate_file(self, compiled, source, dir, fn, initcode):  # @ReservedAssignment
        # This can only be a .rpyc file, since we're loading it
        # from an archive.

        if dir is None:

            rpyfn = fn + source
            lastfn = fn + compiled
            data, stmts = self.load_file(dir, fn + compiled)

            if data is None:
                raise Exception("Could not load from archive %s." % (lastfn,))

            digest = getattr(self, "_last_digest", None)
            if digest is None:
                f = renpy.loader.load(fn + compiled)
                f.seek(-hashlib.md5().digest_size, 2)
                digest = f.read(hashlib.md5().digest_size)
                f.close()

        else:

            # Otherwise, we're loading from disk. So we need to decide if
            # we want to load the rpy or the rpyc file.
            rpyfn = dir + "/" + fn + source
            rpycfn = dir + "/" + fn + compiled

            renpy.loader.add_auto(rpyfn)

            if os.path.exists(rpyfn):
                with open(rpyfn, "rU") as f:
                    rpydigest = hashlib.md5(f.read()).digest()
            else:
                rpydigest = None

            try:
                if os.path.exists(rpycfn):
                    with open(rpycfn, "rb") as f:
                        f.seek(-hashlib.md5().digest_size, 2)
                        rpycdigest = f.read(hashlib.md5().digest_size)
                else:
                    rpycdigest = None
            except:
                rpycdigest = None

            if os.path.exists(rpyfn) and os.path.exists(rpycfn):

                # Are we forcing a compile?
                force_compile = renpy.game.args.compile  # @UndefinedVariable

                # Use the source file here since it'll be loaded if it exists.
                lastfn = rpyfn

                data, stmts = None, None

                try:

                    if rpydigest == rpycdigest and not force_compile:

                        data, stmts = self.load_file(dir, fn + compiled)

                        if data is None:
                            print("Could not load " + rpycfn)

                except:
                    if "RENPY_RPYC_EXCEPTIONS" in os.environ:
                        print("While loading", rpycfn)
                        raise

                    pass

                if data is None:
                    data, stmts = self.load_file(dir, fn + source)

                digest = rpydigest

            elif os.path.exists(rpycfn):
                lastfn = rpycfn
                data, stmts = self.load_file(dir, fn + compiled)

                digest = rpycdigest

            elif os.path.exists(rpyfn):
                lastfn = rpyfn
                data, stmts = self.load_file(dir, fn + source)

                digest = rpydigest

            self.backup_list.append((rpyfn, digest))

        if data is None:
            raise Exception("Could not load file %s." % lastfn)

        # Check the key.
        if self.key is None:
            self.key = data['key']
        elif self.key != data['key']:
            raise Exception( fn + " does not share a key with at least one .rpyc file. To fix, delete all .rpyc files, or rerun Ren'Py with the --lock option.")

        self.finish_load(stmts, initcode, filename=fn + source)

        self.digest.update(digest)

    def init_bytecode(self):
        """
        Init/Loads the bytecode cache.
        """

        # Load the oldcache.
        try:
            version, cache = loads(renpy.loader.load(BYTECODE_FILE).read().decode("zlib"))
            if version == BYTECODE_VERSION:
                self.bytecode_oldcache = cache
        except:
            pass

    def update_bytecode(self):
        """
        Compiles the PyCode objects in self.all_pycode, updating the
        cache. Clears out self.all_pycode.
        """

        # Update all of the PyCode objects in the system with the loaded
        # bytecode.
        for i in self.all_pycode:

            key = i.get_hash() + MAGIC

            code = self.bytecode_oldcache.get(key, None)

            if code is None:

                self.bytecode_dirty = True

                old_ei = renpy.game.exception_info
                renpy.game.exception_info = "While compiling python block starting at line %d of %s." % (i.location[1], i.location[0])

                try:

                    if i.mode == 'exec':
                        code = renpy.python.py_compile_exec_bytecode(i.source, filename=i.location[0], lineno=i.location[1])
                    elif i.mode == 'eval':
                        code = renpy.python.py_compile_eval_bytecode(i.source, filename=i.location[0], lineno=i.location[1])

                except SyntaxError as e:

                    text = e.text

                    if text is None:
                        text = ''

                    try:
                        text = text.decode("utf-8")
                    except:
                        text = text.decode("latin-1")

                    pem = renpy.parser.ParseError(
                        filename=e.filename,
                        number=e.lineno,
                        msg=e.msg,
                        line=text,
                        pos=e.offset)

                    renpy.parser.parse_errors.append(pem.message)

                    continue

                renpy.game.exception_info = old_ei

            self.bytecode_newcache[key] = code
            i.bytecode = marshal.loads(code)

        self.all_pycode = [ ]

    def save_bytecode(self):
        if renpy.macapp:
            return

        if self.bytecode_dirty:
            try:
                fn = renpy.loader.get_path(BYTECODE_FILE)

                with open(fn, "wb") as f:
                    data = (BYTECODE_VERSION, self.bytecode_newcache)
                    f.write(dumps(data, 2).encode("zlib"))
            except:
                pass

    def lookup(self, label):
        """
        Looks up the given label in the game. If the label is not found,
        raises a ScriptError.
        """

        label = renpy.config.label_overrides.get(label, label)
        original = label

        rv = self.namemap.get(label, None)

        if (rv is None) and (renpy.config.missing_label_callback is not None):
            label = renpy.config.missing_label_callback(label)
            rv = self.namemap.get(label, None)

        if rv is None:
            raise ScriptError("could not find label '%s'." % str(original))

        return self.namemap[label]

    def has_label(self, label):
        """
        Returns true if the label exists, or false otherwise.
        """

        label = renpy.config.label_overrides.get(label, label)

        return label in self.namemap

    def analyze(self):
        """
        Analyzes all statements that need analysis.
        """

        for i in self.need_analysis:
            i.analyze()

        self.need_analysis = [ ]

    def report_duplicate_labels(self):
        if not renpy.config.developer:
            return

        if renpy.config.ignore_duplicate_labels:
            return

        renpy.parser.parse_errors = self.duplicate_labels

        if renpy.parser.report_parse_errors():
            raise SystemExit(-1)
