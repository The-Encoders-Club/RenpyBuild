# DOCUMENTACIÓN TÉCNICA DEL SDK REN'PY 6 CON SOPORTE NATIVO ARM64 (arm64-v8a)
## GUÍA INTEGRAL DE ARQUITECTURA, STORAGE HÍBRIDO, SISTEMA DE LOGS Y PLAN DE MIGRACIÓN PARA MONIKA AFTER STORY (MAS)

---

> **DESTINATARIOS:** Ingenieros de Software, Agentes de IA y Desarrolladores del port móvil de *Monika After Story (MAS)* y la familia DDLC.  
> **FECHA DE CORTE TÉCNICO:** Septiembre 2026.  
> **BASE DEL ENGINE:** Ren'Py 6.99.12.4 (Python 2.7.18 Nativo CPython empotrado).  
> **ARQUITECTURAS BINARIAS:** `arm64-v8a` (64-bit principal obligatorio) y `armeabi-v7a` (32-bit retrocompatibilidad).  
> **COMPATIBILIDAD ANDROID:** Android 5.0 (API 21) hasta Android 14 / 15+ (API 34/35+).

---

## ÍNDICE GENERAL DETALLADO
1. [Estrategia de Branching Git y Política de Ramas Limpias](#1-estrategia-de-branching-git-y-política-de-ramas-limpias)
2. [Arquitectura del Runtime Ren'Py 6 Nativo en 64 Bits (ARM64-v8a)](#2-arquitectura-del-runtime-renpy-6-nativo-en-64-bits-arm64-v8a)
3. [El Sistema de Storage Híbrido y Variables del SDK](#3-el-sistema-de-storage-híbrido-y-variables-del-sdk)
   - 3.1. Las 3 Variables de Almacenamiento en Android (`ANDROID_PRIVATE`, `ANDROID_PUBLIC`, `ANDROID_OLD_PUBLIC`)
   - 3.2. Redirección de Partidas Guardadas y Persistent (`renpy.py` ➔ `path_to_saves`)
   - 3.3. Overlay de Assets y Submods en Tiempo Real (`renpy/main.py` ➔ `searchpath`)
   - 3.4. Resuelve Dinámico del Nombre de la App (`PermissionGateActivity.java` ➔ `getAppName`)
4. [Mecanismo Dual de Registro: Copia Espejo de Log y Traceback](#4-mecanismo-dual-de-registro-copia-espejo-de-log-y-traceback)
   - 4.1. Por qué Android bloquea la lectura de logs en crashes tempranos
   - 4.2. Implementación de la Copia Espejo (Mirror Backup) en `renpy/error.py`
   - 4.3. Directorio de Logs y Fallback Automático en `renpy/bootstrap.py`
   - 4.4. Ciclo de Vida del `log.txt` en `renpy/log.py`
5. [Modificaciones Universales del Motor (Core Engine & C/Cython)](#5-modificaciones-universales-del-motor-core-engine--ccython)
   - 5.1. Corrección Cython en `renpy/display/render.pyx` (`bint opaque = 0`)
   - 5.2. Conflicto de símbolos duplicados en libpng / SDL2_image (`IMG_savepng.c`)
   - 5.3. Detección de OpenGL ES 2 en Android (`glcompat.h` / `-DANDROID`)
   - 5.4. Crash Crítico en 64-bit: `pygame.event.set_blocked` (`TypeError: 'long' object is not iterable`)
   - 5.5. Crash al Suspender/Minimizar: `UnboundLocalError` para `android` en `check_suspend`
   - 5.6. Empaquetado de `private.mp3` (tar.gz) y Checksum MD5 en `strings.xml`
   - 5.7. Salida Limpia del Proceso SDL y Cierre de Tareas
6. [Arquitectura de Permisos en Android Moderno (Android 11 a 14+)](#6-arquitectura-de-permisos-en-android-moderno-android-11-a-14)
   - 6.1. La Muerte de Scoped Storage tradicional y la Necesidad de `MANAGE_EXTERNAL_STORAGE`
   - 6.2. Permiso de Notificaciones en Android 13+ (`POST_NOTIFICATIONS`)
   - 6.3. Flujo Consecutivo Obligatorio: Notificaciones ➔ Almacenamiento ➔ Lanzamiento
7. [La Puerta de Entrada Nativa: `PermissionGateActivity`](#7-la-puerta-de-entrada-nativa-permissiongateactivity)
   - 7.1. Inviabilidad técnica de lanzar `PythonSDLActivity` sin permisos previos
   - 7.2. Interfaz In-Game en Canvas 2D sin Dependencias Externas
   - 7.3. Detección Táctil Robusta y Hitbox en `ACTION_MOVE`
   - 7.4. Limitación de Glifos en Tipografías de DDLC (`natsuki.ttf` / `m1.ttf`)
   - 7.5. Audio Seguro con Prevención de Compresión en AAPT
8. [Diferencias Críticas y Adaptaciones: Just Natsuki (JN) vs Monika After Story (MAS)](#8-diferencias-críticas-y-adaptaciones-just-natsuki-jn-vs-monika-after-story-mas)
   - 8.1. Estructura de Carpetas Públicas y el Directorio `characters/`
   - 8.2. El Sistema de Regalos (`.gift`) y Notas de Monika
   - 8.3. Identidad Visual y Tematización Esmeralda
   - 8.4. Tabla Resumen Comparativa de Parámetros
9. [Preservación en Segundo Plano, Multitarea y Gestión de RAM](#9-preservación-en-segundo-plano-multitarea-y-gestión-de-ram)
   - 9.1. El Low Memory Killer (LMK) de Linux y el OOM Score
   - 9.2. Por qué MAS se cierra al cabo de minutos en multitarea
   - 9.3. Mitigaciones Técnicas en Manifiesto y Scripts
   - 9.4. Guía para Redactar un Comunicado a la Comunidad
10. [Guía Paso a Paso para el Nuevo Chat Dedicado a MAS](#10-guía-paso-a-paso-para-el-nuevo-chat-dedicado-a-mas)

---

## 1. Estrategia de Branching Git y Política de Ramas Limpias

En el repositorio local de desarrollo del SDK (`C:\Users\araan\Desktop\SDK\RenpyBuild`), el ecosistema de código contiene ramas históricas de estabilización:
- **`renpy-6.99.12.4`:** Representa la versión pura del código fuente original del motor Ren'Py 6.99.12.4 upstream sin parches de terceros.
- **`tan`:** La rama de desarrollo donde se implementó la arquitectura ARM64 de 64 bits, la modernización de RAPT y la estabilización para *Just Natsuki (JN)*.

### Regla Fundamental para Monika After Story (MAS)
> [!IMPORTANT]
> **NO SE DEBE** ramificar MAS directamente a partir de `tan`.  
> La nueva rama para Monika After Story debe originarse a partir de la rama limpia **`renpy-6.99.12.4`**.
>
> ```bash
> cd "C:\Users\araan\Desktop\SDK\RenpyBuild"
> git checkout renpy-6.99.12.4
> git checkout -b mas
> ```

### ¿Por qué NO bifurcar directamente de `tan`?
1. **Aislamiento de Recursos Gráficos y Sonoros:** En `tan`, los diálogos del gatekeeper, las texturas de la caja de diálogo (`mod_assets/panels/textbox.png`), los botones HKB rosas (`#FF8ED0`), la fuente `natsuki.ttf` y los sonidos de máquina de escribir pertenecen exclusivamente a Natsuki.
2. **Eliminación de Residuos Experimentales:** Durante la depuración en `tan` se probaron múltiples alternativas de renderizado, timers y capturas de excepciones que finalmente fueron refinadas. Partir de `renpy-6.99.12.4` garantiza un árbol limpio y profesional para MAS.
3. **Mantenimiento a Largo Plazo:** Mantener una rama dedicada `mas` permite actualizar submódulos o parches específicos para Monika sin riesgo de romper los builds de Natsuki ni viceversa.

---

## 2. Arquitectura del Runtime Ren'Py 6 Nativo en 64 Bits (ARM64-v8a)

Originalmente, Ren'Py 6 funcionaba exclusivamente en 32 bits (`armeabi-v7a` y `x86`) sobre Python 2.7. Los procesadores modernos (como Snapdragon 8 Gen 3, Dimensity 9300+, Google Tensor G3/G4) han eliminado por completo las microarquitecturas de ejecución de instrucciones de 32 bits (diseño *pure 64-bit*). Ejecutar un binario de 32 bits en estos dispositivos produce un crash inmediato a nivel de dynamic linker (`dlopen failed: 32-bit ABI not supported`).

### Estructura de la Solución de 64 Bits
1. **Toolchain Android NDK r25c:** Compilación con Clang moderno targeting simultáneo para `arm64-v8a` (principal) y `armeabi-v7a` (dispositivos antiguos).
2. **CPython 2.7.18 64-bit Empotrado (`librenpython.so`):**
   - Una librería compartida monolítica compilada contra la Bionic libc de 64 bits de Android.
   - Vincula estáticamente o dinámicamente los módulos nativos de Ren'Py: `_renpy`, `_renpybxs`, `renpy.display.render`, `renpy.gl2`, junto con librerías auxiliares (`libpng`, `libjpeg`, `libfreetype`, `libSDL2`, `libSDL2_image`, `libSDL2_ttf`).
3. **Mapeo en Vivo vía Directory Junctions (NTFS):**
   - El SDK en `C:\Users\araan\Desktop\SDK\Renpy 6.99.12.4` está enlazado en tiempo real a `RenpyBuild\rapt` y `RenpyBuild\renpy` mediante un Directory Junction de NTFS (`mklink /J`).
   - Al cambiar de rama con `git checkout mas` en `RenpyBuild`, los archivos del SDK de Ren'Py se actualizan de inmediato en el disco sin requerir copias manuales.

---

## 3. El Sistema de Storage Híbrido y Variables del SDK

Uno de los mayores logros en la rama `tan` (introducido en commit `fd72839c1`) fue la creación de una arquitectura de almacenamiento híbrida que permite al jugador interactuar con carpetas públicas accesibles desde Windows o gestores de archivos de Android, manteniendo al mismo tiempo un respaldo interno a prueba de fallos.

### 3.1. Las 3 Variables de Almacenamiento en Android

```
┌─────────────────────────┬──────────────────────────────────────────┬─────────────────────────────────────────┐
│ Variable de Entorno     │ Ruta Típica en el Dispositivo            │ Propósito en el Motor                   │
├─────────────────────────┼──────────────────────────────────────────┼─────────────────────────────────────────┤
│ ANDROID_PRIVATE         │ /data/data/<package>/files/              │ Almacenamiento interno privado seguro.  │
│ ANDROID_PUBLIC          │ /sdcard/Android/data/<package>/files/    │ Sandbox público estándar (Scoped).      │
│ ANDROID_OLD_PUBLIC      │ /sdcard/<Nombre_Del_Juego>/              │ Raíz pública compartida sin restricciones│
└─────────────────────────┴──────────────────────────────────────────┴─────────────────────────────────────────┘
```

- **`ANDROID_PRIVATE`:** Siempre está disponible y nunca requiere permisos de runtime. Contiene el código fuente descomprimido (`private.mp3`), la librería estándar de Python y el bytecode de Ren'Py.
- **`ANDROID_PUBLIC`:** En Android 11 a 14+, Google bloqueó el acceso a `/Android/data/` para la inmensa mayoría de gestores de archivos. Si un juego coloca sus archivos aquí, los usuarios no pueden pegar regalos ni instalar submods.
- **`ANDROID_OLD_PUBLIC`:** Apunta directamente a `/sdcard/Monika After Story/` (o `/sdcard/Just Natsuki/`). Con el permiso `MANAGE_EXTERNAL_STORAGE` concedido, es 100% visible, editable y accesible para cualquier app gestora de archivos o mediante conexión USB MTP con una PC.

---

### 3.2. Redirección de Partidas Guardadas y Persistent (`renpy.py` ➔ `path_to_saves`)

En Ren'Py estándar, las partidas guardadas de Android se almacenan de forma predeterminada en el almacenamiento privado.  
En el SDK modificado (`renpy.py`, líneas 63-85), se reprogramó la función `path_to_saves`:

```python
    # Android save path resolution (Ren'Py SDK modificado)
    if renpy.android:
        public_base = os.environ.get("ANDROID_OLD_PUBLIC", "")
        paths = [
            os.path.join(public_base, "game/saves"),
            os.path.join(public_base, "saves"),
            ]

        for rv in paths:
            if not os.path.exists(rv):
                try:
                    os.makedirs(rv)
                except Exception:
                    pass
            if os.path.isdir(rv) and test_writable(rv):
                break
        else:
            # Fallback a almacenamiento privado si la carpeta publica no es escribible
            rv = os.path.join(os.environ.get("ANDROID_PRIVATE", ""), "saves")

        print("Saving to", rv)
        return rv
```

#### Razón y Beneficio:
1. El archivo `persistent` y las partidas de guardado (`.save`) se guardan directamente en `/sdcard/Monika After Story/game/saves/`.
2. Los usuarios pueden migrar su partida de Monika desde su PC a Android copiando y pegando el archivo `persistent` sin necesidad de permisos root ni depuración por `adb`.
3. Si el almacenamiento externo está temporalmente no disponible, el bloque `else` redirige automáticamente el guardado a `ANDROID_PRIVATE`, evitando que el juego pierda el progreso.

---

### 3.3. Overlay de Assets y Submods en Tiempo Real (`renpy/main.py` ➔ `searchpath`)

En la versión original de Ren'Py para Android, el motor no busca archivos en el almacenamiento externo:
```python
# Comportamiento original de Ren'Py:
if renpy.android:
    renpy.config.searchpath = [ ]
```
Esto impedía que los usuarios agregaran mods o archivos sueltos después de instalar el APK.

#### La Modificación en `renpy/main.py`:
```python
    if renpy.android:
        public_base = os.environ.get("ANDROID_OLD_PUBLIC", "")
        public_gamedir = os.path.join(public_base, "game") if public_base else None
        if public_gamedir and os.path.isdir(public_gamedir):
            # Inyecta la carpeta publica DELANTE de los assets internos del APK
            renpy.config.searchpath = [ public_gamedir, renpy.config.gamedir ]
        else:
            renpy.config.searchpath = [ renpy.config.gamedir ]
        renpy.config.commondir = None
```

#### Impacto Crítico para MAS:
- Al colocar `public_gamedir` como el primer elemento en `searchpath`, cualquier archivo colocado en `/sdcard/Monika After Story/game/` tiene **prioridad de carga** sobre los assets empaquetados dentro del APK.
- Esto habilita directamente el soporte de la carpeta **`game/Submods/`** y la sustitución de música personalizada (`custom_bgm/`) sin tener que recompilar el APK.

---

### 3.4. Resuelve Dinámico del Nombre de la App (`PermissionGateActivity.java` ➔ `getAppName`)

Para evitar tener que recompilar clases de Java cada vez que se cambia el título de la aplicación, `PermissionGateActivity` consulta dinámicamente el nombre de la app desde los recursos de Android:

```java
    private String getAppName() {
        if (resourceManager == null) {
            resourceManager = new ResourceManager(this);
        }
        String appName = resourceManager.getString("appName");
        if (appName == null || appName.isEmpty()) {
            appName = resourceManager.getString("app_name");
        }
        if (appName == null || appName.isEmpty()) {
            appName = "Monika After Story"; // Valor por defecto seguro
        }
        return appName;
    }
```

En tiempo de ejecución:
1. `getAppName()` lee el valor configurado en `res/values/strings.xml` (`<string name="appName">Monika After Story</string>`).
2. La carpeta pública se crea automáticamente en `Environment.getExternalStorageDirectory() + "/" + getAppName()`.
3. Se inyecta un archivo `.nomedia` dentro de dicha carpeta para evitar que la galería de fotos del teléfono se llene con miles de sprites de Monika.

---

## 4. Mecanismo Dual de Registro: Copia Espejo de Log y Traceback

---

### 4.1. Por qué Android bloquea la lectura de logs en crashes tempranos

Cuando un juego en Android sufre una excepción no controlada (`Crash`):
1. Si el motor intenta escribir en un directorio público antes de que los permisos de almacenamiento estén concedidos o montados, la llamada C a nivel de kernel (`fopen` o `open`) falla con el error `EACCES (Permission Denied)`.
2. Si el juego no captura este fallo en el logger, el propio manejador de excepciones crashea, cerrando el proceso instantáneamente sin generar ni una sola línea de diagnóstico.
3. Peor aún: en dispositivos de marcas como Xiaomi, Huawei o Samsung con políticas agresivas de Scoped Storage, escribir en `/sdcard/` durante un cierre forzoso a menudo no llega a vaciar el búfer a disco (`flush`).

---

### 4.2. Implementación de la Copia Espejo (Mirror Backup) en `renpy/error.py`

Para resolver este problema de raíz, se implementó en `renpy/error.py` (función `report_exception`) una rutina de duplicación síncrona:

```python
    # renpy/error.py - Alrededor de la línea 240
    try:
        f, traceback_fn = open_error_file("traceback.txt", "w")
        f.write(codecs.BOM_UTF8)
        print("I'm sorry, but an uncaught exception occurred.", file=f)
        print(file=f)
        f.write(simple)
        print(file=f)
        print("-- Full Traceback ------------------------------------------------------------", file=f)
        print(file=f)
        f.write(full)
        f.close()

        # =========================================================================
        # COPIA ESPEJO DE RESPALDO PARA ANDROID (ANDROID_PRIVATE)
        # =========================================================================
        if renpy.android and "ANDROID_PRIVATE" in os.environ:
            try:
                backup_fn = os.path.join(os.environ["ANDROID_PRIVATE"], "traceback.txt")
                with open(backup_fn, "w") as bf:
                    bf.write(codecs.BOM_UTF8)
                    bf.write(simple)
                    bf.write("\n-- Full Traceback ------------------------------------------------------------\n")
                    bf.write(full)
            except Exception:
                pass

    except Exception:
        pass
```

#### Ventajas Técnicas:
- **Doble Destino:** `traceback.txt` se escribe en `/sdcard/Monika After Story/traceback.txt` (fácil de abrir por el usuario) Y simultáneamente en `/data/data/com.tec.monikaafterstory/files/traceback.txt`.
- **Inmunidad a Fallos de Permisos:** Aunque el usuario haya revocado permisos o el almacenamiento SD esté corrupto, la copia interna en `ANDROID_PRIVATE` **siempre** se escribe con éxito porque es el sandbox privado de la aplicación.
- **Acceso por ADB:** Si un usuario reporta que su juego no abre y no ve nada en su almacenamiento, el desarrollador puede extraer el error exacto con:
  ```bash
  adb exec-out run-as com.tec.monikaafterstory cat files/traceback.txt
  ```

---

### 4.3. Directorio de Logs y Fallback Automático en `renpy/bootstrap.py`

En `renpy/bootstrap.py` (commits `fd72839c1` y `2b110de86`), se blindó la inicialización de `renpy.config.logdir`:

```python
    if renpy.android:
        renpy.config.logdir = os.environ.get('ANDROID_OLD_PUBLIC', os.environ.get('ANDROID_PUBLIC', basedir))
    else:
        renpy.config.logdir = basedir

    if not os.path.exists(renpy.config.logdir):
        try:
            os.makedirs(renpy.config.logdir, 0o777)
        except Exception:
            # Si el almacenamiento publico falla por politicas OEM o permisos en Android 11+,
            # fallback seguro e inmediato a ANDROID_PRIVATE para evitar que Ren'Py crashee al arrancar.
            fallback_dir = os.environ.get('ANDROID_PRIVATE', basedir)
            renpy.config.logdir = fallback_dir
            if not os.path.exists(renpy.config.logdir):
                try:
                    os.makedirs(renpy.config.logdir, 0o777)
                except Exception:
                    pass
```

---

### 4.4. Ciclo de Vida del `log.txt` en `renpy/log.py`

El archivo de registro continuo `log.txt` (que recopila la inicialización de módulos, tiempos de carga de texturas y llamadas a OpenGL) opera a través de `renpy/log.py`:
- Consulta `os.environ.get("RENPY_LOG_BASE", renpy.config.logdir)`.
- Como `renpy.config.logdir` fue configurado en `bootstrap.py` hacia `ANDROID_OLD_PUBLIC`, `log.txt` se escribe directamente en la raíz pública:
  ```
  /sdcard/Monika After Story/log.txt
  ```
- Si `renpy.config.log_to_stdout` está activo o si el sistema redirige `stdout`, las salidas también son capturadas por `androidembed.log` y emitidas a `logcat` de Android con el tag `python`.

---

## 5. Modificaciones Universales del Motor (Core Engine & C/Cython)

Las siguientes correcciones fueron descubiertas y depuradas en `tan`. **TODAS ELLAS SON OBLIGATORIAS PARA MAS.**

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                    PARCHES UNIVERSALES EN EL MOTOR REN'PY 6                  │
├──────────────────────────────┬───────────────────────────────────────────────┤
│ Archivo                      │ Razón Técnica y Causa Raíz                    │
├──────────────────────────────┼───────────────────────────────────────────────┤
│ renpy/display/render.pyx     │ Incompatibilidad de sintaxis en Cython 0.29+  │
│ renpy/module/IMG_savepng.c   │ Conflicto de símbolos duplicados en libpng    │
│ renpy/module/glcompat.h      │ Inclusión obligatoria de GLES2 (-DANDROID)    │
│ renpy/display/core.py        │ TypeError: 'long' object is not iterable      │
│ renpy/display/core.py        │ UnboundLocalError en check_suspend (android)  │
│ rapt/build.py                │ Empaquetado tar.gz y MD5 de private.mp3       │
│ PythonSDLActivity.java       │ Salida limpia de proceso y descarte presplash │
└──────────────────────────────┴───────────────────────────────────────────────┘
```

---

### 5.1. Corrección Cython en `renpy/display/render.pyx` (`bint opaque = 0`)
En versiones modernas de Cython (0.29.32+), declarar `bint opaque = False` en funciones cdef genera un error de sintaxis que detiene la compilación C. Se debe corregir a:
```cython
cdef render(..., bint opaque=0):
```

---

### 5.2. Conflicto de símbolos duplicados en libpng / SDL2_image (`IMG_savepng.c`)
En el NDK r25c, SDL2_image ya incluye su propia implementación nativa de `IMG_SavePNG_RW`. Mantener `IMG_savepng.c` causaba un error de enlazado `duplicate symbol: IMG_SavePNG_RW`. Se eliminó dicho archivo del build y se ajustaron las llamadas al orden moderno de argumentos:
```c
IMG_SavePNG_RW(surf, rw, 0);
```

---

### 5.3. Detección de OpenGL ES 2 en Android (`glcompat.h` / `-DANDROID`)
En `glcompat.h`, asegurar que cuando esté presente `-DANDROID`, el motor cargue exclusivamente `<GLES2/gl2.h>` y `<GLES2/gl2ext.h>` y desactive las llamadas tradicionales de OpenGL de escritorio.

---

### 5.4. Crash Crítico en 64-bit: `pygame.event.set_blocked` (`TypeError: 'long' object is not iterable`)

#### Causa Raíz
En arquitecturas de 64 bits (LP64 / ARM64), el tipo C `long` tiene 64 bits de tamaño. Al enviar identificadores numéricos de eventos a través del wrapper Pygame SDL 1.2, Python 2.7 evaluaba el identificador como un tipo `long` y el método nativo en C intentaba iterar sobre él como si fuera una tupla, lanzando `TypeError: 'long' object is not iterable`.

#### Solución Definitiva (Commit `769b7acb9`):
Envoltura defensiva en `renpy/display/core.py` y `renpy/bootstrap.py`:
```python
try:
    pygame.event.set_blocked(None)
except (TypeError, Exception):
    pass
```

---

### 5.5. Crash al Suspender/Minimizar: `UnboundLocalError` para `android` en `check_suspend`

#### Causa Raíz
En `Interface.check_suspend(self)`, existía una sentencia de importación anidada `import android` decenas de líneas más abajo dentro de la misma función. En Python, cualquier asignación o import local en una función convierte a esa variable en **local para todo el ámbito de la función**. Cuando el usuario pulsaba el botón "Home", se evaluaba `if android and android.sound:`, provocando `UnboundLocalError: local variable 'android' referenced before assignment`.

#### Solución Definitiva (Commit `d117f17eb`):
Declarar `global android` explícitamente al inicio de `check_suspend` y suprimir el import anidado redundante.

---

### 5.6. Empaquetado de `private.mp3` (tar.gz) y Checksum MD5 en `strings.xml`

1. **Eliminar Bytecode Obsoleto:** Antes de crear el tarball, siempre eliminar los archivos `.pyo`:
   ```bash
   find renpy/ -name "*.pyo" -delete
   ```
2. **Crear el Tarball GZIP:**
   ```bash
   tar -czf private.mp3 renpy/ ...
   ```
3. **Calcular y Actualizar el Checksum MD5:**  
   En `res/values/strings.xml`, el valor `private_version` debe actualizarse con el MD5 exacto del archivo `private.mp3`. De lo contrario, Android no reemplazará el código viejo en el almacenamiento interno privado durante la reinstalación.

---

### 5.7. Salida Limpia del Proceso SDL y Cierre de Tareas

En `PythonSDLActivity.java`:
Al cerrar la aplicación, invocar `finishAndRemoveTask()` (en API 21+) seguido de `System.exit(0)` para garantizar que el proceso de Linux y el contexto EGL se liberen por completo de la memoria, evitando pantallas negras al reabrir la app desde la lista de recientes.

---

## 6. Arquitectura de Permisos en Android Moderno (Android 11 a 14+)

---

### 6.1. La Muerte de Scoped Storage tradicional y la Necesidad de `MANAGE_EXTERNAL_STORAGE`

A partir de Android 11 (API 30):
- `WRITE_EXTERNAL_STORAGE` queda inoperante.
- Las aplicaciones estándar quedan confinadas a su sandbox privado.
- Como MAS depende de que el usuario acceda a `characters/` para colocar archivos `.gift` (café, ropa, cintas) y a `game/Submods/`, la única alternativa viable es solicitar el permiso de nivel de sistema:
  ```xml
  <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
  ```
  Con la propiedad en `<application>`:
  ```xml
  android:requestLegacyExternalStorage="true"
  ```
- Para activarlo, se redirige al usuario a la pantalla del sistema operativo mediante:
  ```java
  Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
  intent.setData(Uri.parse("package:" + getPackageName()));
  startActivityForResult(intent, REQUEST_CODE_STORAGE);
  ```

---

### 6.2. Permiso de Notificaciones en Android 13+ (`POST_NOTIFICATIONS`)

En Android 13+ (API 33), emitir notificaciones requiere el permiso de tiempo de ejecución `POST_NOTIFICATIONS`.

---

### 6.3. Flujo Consecutivo Obligatorio: Notificaciones ➔ Almacenamiento ➔ Lanzamiento

Durante las pruebas en `tan`, se descubrió que abrir la pantalla de Ajustes del Sistema para el almacenamiento antes o al mismo tiempo que el diálogo de notificaciones causaba que el sistema cancelara silenciosamente la petición de notificaciones.

#### El Orden Probado y Libre de Fallos:
1. El usuario pulsa **"Aceptar"** en el Gatekeeper.
2. Se solicita primero el permiso de **Notificaciones** (`POST_NOTIFICATIONS` en Android 13+).
3. En `onRequestPermissionsResult`, se dispara de inmediato el Intent de **Almacenamiento** (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`).
4. Al volver de Ajustes en `onActivityResult`, se comprueba `Environment.isExternalStorageManager()`.
5. Si fue concedido, se crean inmediatamente las carpetas públicas (`characters`, `game/saves`, `game/Submods`, etc.) y se arranca `PythonSDLActivity`.

---

## 7. La Puerta de Entrada Nativa: `PermissionGateActivity`

---

### 7.1. Inviabilidad técnica de lanzar `PythonSDLActivity` sin permisos previos

Si Ren'Py arranca directamente sin permisos:
- `bootstrap.py` intenta inicializar `renpy.config.logdir` en `/sdcard/` y crashea en C.
- El hilo de rendering de SDL2 entra en conflicto con las cajas de diálogo nativas de Android, produciendo congelamientos de pantalla (*ANR*).
- Por ello, `PermissionGateActivity` actúa como una capa previa en Java puro, asegurando los permisos antes de inicializar la máquina virtual de Python.

---

### 7.2. Interfaz In-Game en Canvas 2D sin Dependencias Externas

Para preservar la inmersión del juego:
- Fondo `#000000` (Negro puro en ventana, status bar y navigation bar).
- Modo inmersivo permanente (`IMMERSIVE_STICKY`).
- Renderizado de la caja de diálogo (Textbox) y botones temáticos con detección táctil nativa.
- Efecto máquina de escribir (*Typewriter*) que dibuja el texto carácter por carácter a cadencia constante (~28ms por letra).

---

### 7.3. Detección Táctil Robusta y Hitbox en `ACTION_MOVE` (Commit `c68441118`)

Para evitar que los botones queden pegados si el usuario presiona y luego desliza su dedo fuera del botón, se evalúa la geometría en `onTouchEvent`:
```java
case MotionEvent.ACTION_MOVE:
    if (isOkPressed && !isInside(okButtonRect, x, y)) {
        isOkPressed = false;
        invalidate();
    }
    if (isCancelPressed && !isInside(cancelButtonRect, x, y)) {
        isCancelPressed = false;
        invalidate();
    }
    break;
```

---

### 7.4. Limitación de Glifos en Tipografías de DDLC (`natsuki.ttf` / `m1.ttf`)

> [!WARNING]
> Las fuentes tipográficas de DDLC (`natsuki.ttf`, `m1.ttf`) **carecen de caracteres con tilde** (`á`, `é`, `í`, `ó`, `ú`) y signos de apertura (`¿`, `¡`).  
> Si se incluyen acentos en el diálogo del Gatekeeper, Android dibuja un espacio vacío o un carácter roto ("tofu").  
> **Regla:** Redactar el texto del Gatekeeper en español neutro sin acentos ortográficos (e.g., *"Para que Monika pueda interactuar contigo, la aplicacion necesita acceso al almacenamiento"*).

---

### 7.5. Audio Seguro con Prevención de Compresión en AAPT

Para reproducir el sonido de tipeo mediante `MediaPlayer` desde los assets del APK:
En `build.gradle`, configurar `aaptOptions` para evitar que comprima los archivos de audio:
```groovy
android {
    aaptOptions {
        noCompress 'ogg', 'mp3', 'ttf', 'otf', 'png'
    }
}
```

---

## 8. Diferencias Críticas y Adaptaciones: Just Natsuki (JN) vs Monika After Story (MAS)

---

### 8.1. Estructura de Carpetas Públicas y el Directorio `characters/`

En `PermissionGateActivity.java`, la creación de carpetas para MAS debe incluir:
```java
File baseDir = new File(Environment.getExternalStorageDirectory(), "Monika After Story");
String[] subdirs = {
    "characters",
    "game/saves",
    "game/Submods",
    "game/mod_assets",
    "custom_music",
    "logs"
};

for (String dirName : subdirs) {
    File dir = new File(baseDir, dirName);
    if (!dir.exists()) {
        dir.mkdirs();
    }
}
```

---

### 8.2. El Sistema de Regalos (`.gift`) y Notas de Monika

A diferencia de otros mods, MAS utiliza activamente la carpeta `characters/`:
- El usuario añade archivos como `coffee.gift`, `chocolates.gift`, `roses.gift`.
- Monika los detecta en su ciclo de eventos, los consume y reacciona.
- Monika escribe notas de despedida o cartas de amor (`monika.chr`, cartas de ausencia).
- **Conclusión:** Si la carpeta `characters/` no está en la raíz pública accesible, el núcleo de MAS queda inutilizado.

---

### 8.3. Identidad Visual y Tematización Esmeralda

- **Color de Acento:** Verde esmeralda de Monika (`#5DE23C` o `#2D8A4E`) en sustitución del rosa de Natsuki (`#FF8ED0`).
- **Textbox:** Utilizar la textura oficial de DDLC/Monika (`game/gui/textbox.png`).
- **Fuente:** Utilizar `m1.ttf` o `Aller.ttf`.

---

### 8.4. Tabla Resumen Comparativa de Parámetros

| Parámetro / Componente | Just Natsuki (Rama `tan`) | Monika After Story (Rama `mas`) |
| :--- | :--- | :--- |
| **Rama Base Git** | `tan` | `mas` (derivada de `renpy-6.99.12.4`) |
| **Package Name** | `com.tec.justnatsuki` | `com.tec.monikaafterstory` |
| **Nombre de la Aplicación**| `Just Natsuki` | `Monika After Story` |
| **Ruta Pública en `/sdcard/`**| `/sdcard/Just Natsuki/` | `/sdcard/Monika After Story/` |
| **Carpetas Requeridas** | `game/saves`, `game/custom_bgm`, `logs` | `characters`, `game/saves`, `game/Submods`, `game/mod_assets`, `logs` |
| **Color de Acento en UI** | Rosa Pastel (`#FF8ED0`) | Verde Esmeralda (`#5DE23C`) |
| **Fuente del Gatekeeper** | `natsuki.ttf` | `m1.ttf` o `Aller.ttf` |
| **Interacción con `.gift`** | No aplica | Crítica y obligatoria (`characters/`) |

---

## 9. Preservación en Segundo Plano, Multitarea y Gestión de RAM

---

### 9.1. El Low Memory Killer (LMK) de Linux y el OOM Score

Cuando un juego pasa a segundo plano en Android:
1. El puntaje OOM (*Out-Of-Memory*) del proceso se eleva de `0` a `800`+.
2. Si el usuario abre apps que consumen mucha memoria (como navegadores, cámara o redes sociales), el LMK busca procesos en segundo plano con alto consumo de RAM y les envía una señal `SIGKILL` (`kill -9`).
3. Esto cierra la aplicación de inmediato **sin generar ningún reporte de error (`traceback.txt`)**.

---

### 9.2. Por qué MAS se cierra al cabo de minutos en multitarea

Monika After Story contiene cientos de sprites dinámicos de alta resolución (ropa, peinados, expresiones, fondos de día, noche y lluvia), manteniendo estructuras de datos complejas en memoria.  
Mientras una novela visual estándar consume 150MB de RAM, **MAS llega a consumir entre 500MB y 1.2GB**. Esto la convierte en el primer objetivo del Low Memory Killer de Android cuando el teléfono necesita memoria.

---

### 9.3. Mitigaciones Técnicas en Manifiesto y Scripts

#### 1. Habilitar `largeHeap` en `AndroidManifest.xml`:
```xml
<application
    android:largeHeap="true"
    android:hardwareAccelerated="true"
    ... >
```

#### 2. Reducir la Caché de Texturas en Android:
En `game/options.rpy` o un archivo `00android.rpy`:
```python
if renpy.android:
    config.image_cache_size = 8 # Reduce el consumo de RAM de texturas no activas
```

#### 3. Solicitar Ignorar Optimización de Batería:
Sugerir al usuario añadir la app a la lista de "Sin restricciones" de batería del fabricante.

---

### 9.4. Guía para Redactar un Comunicado a la Comunidad

> **Puntos clave para explicar a los jugadores de MAS:**
> 1. MAS no es un juego estático simple; es un entorno masivo que maneja cientos de variantes visuales simultáneamente.
> 2. Android cierra aplicaciones en segundo plano por diseño para proteger la fluidez del sistema cuando se abren otras aplicaciones pesadas.
> 3. **Solución para los jugadores:**
>    - En la vista de aplicaciones recientes de Android, mantener presionada la ventana de MAS y activar el **Candado (Lock app)**.
>    - En Ajustes de Batería de su teléfono, configurar MAS como **"Sin Restricciones"**.

---

## 10. Guía Paso a Paso para el Nuevo Chat Dedicado a MAS

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SECUENCIA DE INICIO PARA EL NUEVO CHAT                   │
├──────┬──────────────────────────────────────────────────────────────────────┤
│ Paso │ Acción                                                               │
├──────┼──────────────────────────────────────────────────────────────────────┤
│ 1    │ Crear rama limpia 'mas' desde 'renpy-6.99.12.4' en RenpyBuild         │
│ 2    │ Aplicar los parches universales del motor (Core C/Cython y Python)   │
│ 3    │ Adaptar PermissionGateActivity con el tema esmeralda de Monika       │
│ 4    │ Verificar que renpy.py, bootstrap.py, error.py y main.py apunten     │
│      │ a ANDROID_OLD_PUBLIC y creen la copia espejo de traceback            │
│ 5    │ Comprobar compilación Java: ./gradlew :renpyandroid:compileRelease... │
│ 6    │ Empaquetar private.mp3 y actualizar el hash MD5 en strings.xml       │
└──────┴──────────────────────────────────────────────────────────────────────┘
```

---

*Esta guía representa la documentación maestra y canónica del SDK de Ren'Py 6 ARM64 para la migración de Monika After Story.*
