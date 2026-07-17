package eu.kanade.tachiyomi.extension

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.util.zip.ZipFile

@Serializable
data class ExtensionInfo(
    val name: String = "",
    val pkg: String = "",
    val apk: String = "",
    val lang: String = "",
    val version: String = "",
    val nsfw: Int = 0,
    val sources: List<SourceInfo> = emptyList()
)

@Serializable
data class SourceInfo(
    val name: String = "",
    val lang: String = "",
    val id: String = "",
    val baseUrl: String = ""
)

object ExtensionManager {
    // Track classloaders by package name so we can close them on uninstall
    private val classLoaders = mutableMapOf<String, ASMClassLoader>()

    sealed interface LoadedSource {
        data class Anime(val source: AnimeSource) : LoadedSource
        data class Manga(val source: MangaSource) : LoadedSource
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { Injekt.get<NetworkHelper>().client }

    var extensionsDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/extensions")
    val cacheDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/cache")

    init {
        if (!extensionsDir.exists()) extensionsDir.mkdirs()
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        // Clean up old versioned JAR files (e.g. aniyomi-all.animeonsen-v14.10.jar)
        try {
            val oldFiles = extensionsDir.listFiles { _, name -> name.startsWith("aniyomi-") && name.endsWith(".jar") }
            oldFiles?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Clean up any stale .deleted files from previous uninstalls (renameTo fallback)
        try {
            val staleDeleted = extensionsDir.listFiles { _, name -> name.endsWith(".jar.deleted") }
            staleDeleted?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Fetches the repository index JSON
    suspend fun fetchRepository(url: String): List<ExtensionInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Error al descargar repositorio: ${response.code}")
            val body = response.body?.string() ?: throw Exception("Repositorio vacío")
            json.decodeFromString<List<ExtensionInfo>>(body)
        }
    }

    // Downloads the APK and returns the local File
    suspend fun downloadApk(repoBaseUrl: String, apkName: String): File = withContext(Dispatchers.IO) {
        val url = if (repoBaseUrl.endsWith("/")) "$repoBaseUrl$apkName" else "$repoBaseUrl/$apkName"
        val request = Request.Builder().url(url).build()
        val tempApk = File(cacheDir, apkName)
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Error al descargar APK: ${response.code}")
            val bodyStream = response.body?.byteStream() ?: throw Exception("APK vacío")
            FileOutputStream(tempApk).use { output ->
                bodyStream.copyTo(output)
            }
        }
        tempApk
    }

    fun translateApkToJar(apkFile: File, jarFile: File) {
        try {
            if (jarFile.exists()) jarFile.delete()
            val bytes = apkFile.readBytes()
            val reader = MultiDexFileReader.open(bytes)
            Dex2jar.from(reader).to(jarFile.toPath())
        } catch (e: Exception) {
            throw RuntimeException("Error al traducir DEX a JAR: ${e.message}", e)
        }
    }

    // Loads the JAR and returns the list of instantiated sources.
    // Optionally accepts a mutable list to collect per-class error messages.
    fun loadExtension(jarFile: File, errorCollector: MutableList<String>? = null): List<LoadedSource> {
        val urls = arrayOf(jarFile.toURI().toURL())
        val parentClassLoader = this.javaClass.classLoader
        val classLoader = ASMClassLoader(urls, parentClassLoader)
        // Track by JAR filename so we can close the classloader on uninstall
        classLoaders[jarFile.name] = classLoader
        val loadedSources = mutableListOf<LoadedSource>()

        ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.endsWith(".class") && !entry.name.contains("$")) {
                    val className = entry.name
                        .replace('/', '.')
                        .removeSuffix(".class")
                    try {
                        val clazz = classLoader.loadClass(className)
                        // Ignore abstract, interfaces, etc.
                        if (java.lang.reflect.Modifier.isAbstract(clazz.modifiers) || clazz.isInterface) {
                            continue
                        }
                        if (AnimeSource::class.java.isAssignableFrom(clazz)) {
                            val ctor = clazz.getDeclaredConstructor()
                            ctor.isAccessible = true
                            val instance = ctor.newInstance() as AnimeSource
                            loadedSources.add(LoadedSource.Anime(instance))
                        } else if (MangaSource::class.java.isAssignableFrom(clazz)) {
                            val ctor = clazz.getDeclaredConstructor()
                            ctor.isAccessible = true
                            val instance = ctor.newInstance() as MangaSource
                            loadedSources.add(LoadedSource.Manga(instance))
                        } else if (AnimeSourceFactory::class.java.isAssignableFrom(clazz)) {
                            val ctor = clazz.getDeclaredConstructor()
                            ctor.isAccessible = true
                            val factoryInstance = ctor.newInstance() as AnimeSourceFactory
                            loadedSources.addAll(factoryInstance.createSources().map { LoadedSource.Anime(it) })
                        } else if (SourceFactory::class.java.isAssignableFrom(clazz)) {
                            val ctor = clazz.getDeclaredConstructor()
                            ctor.isAccessible = true
                            val factoryInstance = ctor.newInstance() as SourceFactory
                            loadedSources.addAll(factoryInstance.createSources().map { LoadedSource.Manga(it) })
                        }
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        val cause = e.cause
                        val msg = when {
                            cause is java.lang.NoClassDefFoundError -> "Falta clase Android: ${cause.message}"
                            cause is java.lang.ExceptionInInitializerError -> "Error en init/structor: ${cause.message}"
                            else -> "${e.message}"
                        }
                        errorCollector?.add("$className → $msg")
                        println("[LOAD_EXT_ERR] Error al instanciar $className: ${e.message}")
                        if (cause != null) cause.printStackTrace() else e.printStackTrace()
                    } catch (e: java.lang.NoClassDefFoundError) {
                        val msg = "Falta clase: ${e.message}"
                        errorCollector?.add("$className → $msg")
                        println("[LOAD_EXT_ERR] Falta clase para $className: ${e.message}")
                        e.printStackTrace()
                    } catch (e: Throwable) {
                        val msg = "${e.message}"
                        errorCollector?.add("$className → $msg")
                        println("[LOAD_EXT_ERR] Error al cargar clase $className: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        return loadedSources
    }

    // High level method to install an extension: downloads APK, translates to JAR, loads sources, and returns them
    suspend fun installExtension(repoUrl: String, extension: ExtensionInfo): List<LoadedSource> {
        val repoBaseUrl = repoUrl.substringBeforeLast("/") + "/apk/"
        val apkFile = downloadApk(repoBaseUrl, extension.apk)
        val jarFile = File(extensionsDir, extension.pkg + ".jar")
        
        withContext(Dispatchers.IO) {
            translateApkToJar(apkFile, jarFile)
            apkFile.delete() // Clean up the temporary APK
        }
        
        return loadExtension(jarFile)
    }

    // Loads all locally installed extensions from extensions directory
    // Returns the loaded sources AND any errors encountered per file
    fun loadLocalExtensionsWithErrors(blacklist: List<String> = emptyList()): Pair<List<LoadedSource>, Map<String, List<String>>> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".jar") } ?: return Pair(emptyList(), emptyMap())
        val allSources = mutableListOf<LoadedSource>()
        val allErrors = mutableMapOf<String, MutableList<String>>()
        for (file in files) {
            val pkgName = file.name.substringBeforeLast(".jar")
            if (blacklist.contains(pkgName)) {
                println("[ExtensionManager] Skipping blacklisted extension: ${file.name}")
                continue
            }
            val fileErrors = mutableListOf<String>()
            try {
                val loaded = loadExtension(file, fileErrors)
                if (fileErrors.isNotEmpty()) {
                    allErrors[file.name] = fileErrors
                    println("[ExtensionManager] Loaded ${loaded.size} sources from ${file.name} WITH ${fileErrors.size} errors: ${fileErrors.joinToString("; ")}")
                } else {
                    println(
                        "[ExtensionManager] Loaded ${loaded.size} sources from ${file.name}: ${loaded.map { source -> when (source) {
                            is LoadedSource.Anime -> source.source.name
                            is LoadedSource.Manga -> source.source.name
                        } }}",
                    )
                }
                allSources.addAll(loaded)
            } catch (e: Throwable) {
                val msg = "[FATAL] ${e.message}"
                fileErrors.add(msg)
                println("[ExtensionManager] Error loading file ${file.name}: ${e.message}")
                e.printStackTrace()
                allErrors[file.name] = fileErrors
            }
        }
        return Pair(allSources, allErrors)
    }

    // Overload for backward compatibility
    fun loadLocalExtensions(blacklist: List<String> = emptyList()): List<LoadedSource> {
        return loadLocalExtensionsWithErrors(blacklist).first
    }

    /**
     * Uninstall an extension: close its classloader, try to delete the JAR file.
     * Returns (deleted, needsBlacklist, message).
     * - deleted=true  → the JAR was physically removed
     * - needsBlacklist=true → the JAR couldn't be deleted (locked by JVM).
     *   The caller should add the package to blacklistedExtensions so the
     *   extension disappears from the UI immediately. The JAR will be deleted
     *   on next app restart (deleteOnExit()).
     */
    fun uninstallExtension(packageName: String): Triple<Boolean, Boolean, String> {
        val jarName = packageName + ".jar"
        val jarFile = File(extensionsDir, jarName)

        if (!jarFile.exists()) return Triple(true, false, "")

        // 1. Close the classloader to release the JAR file handle
        val cl = classLoaders.remove(jarName)
        if (cl != null) {
            try {
                cl.close()
                println("[ExtensionManager] Closed classloader for $jarName")
            } catch (e: Exception) {
                println("[ExtensionManager] Error closing classloader for $jarName: ${e.message}")
            }
        }

        // 2. Give GC a chance to clean up
        System.gc()
        System.runFinalization()

        // 3. Try to delete with retries
        var deleted = false
        for (attempt in 1..3) {
            if (jarFile.exists()) {
                deleted = jarFile.delete()
                if (deleted) break
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                System.gc()
            } else {
                deleted = true
                break
            }
        }

        if (deleted) {
            println("[ExtensionManager] Successfully deleted $jarName")
            val stale = File(extensionsDir, "$jarName.deleted")
            if (stale.exists()) stale.delete()
            return Triple(true, false, "")
        }

        // All delete attempts failed — schedule for next restart
        jarFile.deleteOnExit()
        println("[ExtensionManager] Could not delete $jarName (JVM lock). Added to deleteOnExit().")
        // Return needsBlacklist=true so the caller blacklists it immediately
        return Triple(false, true, "El JAR está bloqueado por el sistema. Se ocultará ahora y se eliminará al reiniciar la aplicación.")
    }
}

class ASMClassLoader(urls: Array<java.net.URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(this) {
            val loadedClass = findLoadedClass(name)
            if (loadedClass != null) {
                if (resolve) resolveClass(loadedClass)
                return loadedClass
            }
            // Load extension classes directly from the JAR and patch bytecode on the fly.
            // Classes that must come from the JVM (java.*, javax.*) or Android shim
            // (android.*) are always delegated to the parent classloader.
            val path = name.replace('.', '/') + ".class"
            if (!name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("android.")) {
                val res = findResource(path)
                if (res != null) {
                    try {
                        res.openStream().use { stream ->
                            val originalBytes = stream.readBytes()
                            // Patch away any calls to kotlin.Result.constructor_impl
                            // (and similar Kotlin value-class synthetic methods) that
                            // don't exist in the desktop Kotlin stdlib.
                            val patchedBytes = patchKotlinInlineClassCalls(originalBytes)
                            val clazz = defineClass(name, patchedBytes, 0, patchedBytes.size)
                            if (resolve) resolveClass(clazz)
                            return clazz
                        }
                    } catch (e: Throwable) {
                        println("[ASMClassLoader] Error loading class $name: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    /**
     * Uses ASM to rewrite INVOKESTATIC calls to Kotlin value-class synthetic methods
     * on kotlin/Result that are absent (or have different signatures) in the desktop
     * Kotlin stdlib. All calls are redirected to KotlinResultCompat which provides
     * correct implementations for every known synthetic method.
     *
     * Known kotlin/Result synthetic methods and their replacements:
     *   constructor_impl(Object):Object      → identity (KotlinResultCompat.constructorImpl)
     *   isSuccess_impl(Object):boolean       → KotlinResultCompat.isSuccess
     *   isFailure_impl(Object):boolean       → KotlinResultCompat.isFailure
     *   exceptionOrNull_impl(Object):Throwable → KotlinResultCompat.exceptionOrNull
     *   getOrNull_impl / getValue_impl etc.  → KotlinResultCompat.getOrNull
     *   throwOnFailure_impl(Object):void     → KotlinResultCompat.throwOnFailure
     *   toString_impl(Object):String         → KotlinResultCompat.toStringImpl
     *   hashCode_impl(Object):int            → KotlinResultCompat.hashCodeImpl
     *   equals_impl(Object,Object):boolean   → KotlinResultCompat.equalsImpl
     *   box-impl / box_impl(Object):Object   → KotlinResultCompat.box
     *   unbox-impl / unbox_impl(Object):Object→ KotlinResultCompat.unbox
     */
    private fun patchKotlinInlineClassCalls(bytes: ByteArray): ByteArray {
        val COMPAT = "eu/kanade/tachiyomi/extension/KotlinResultCompat"
        return try {
            val reader = org.objectweb.asm.ClassReader(bytes)
            val writer = org.objectweb.asm.ClassWriter(0)
            val visitor = object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): org.objectweb.asm.MethodVisitor {
                    val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, mv) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            // Only intercept INVOKESTATIC on kotlin/Result synthetic methods
                            if (opcode == org.objectweb.asm.Opcodes.INVOKESTATIC &&
                                owner == "kotlin/Result" &&
                                (name.endsWith("_impl") || name.endsWith("-impl"))
                            ) {
                                println("[ASMClassLoader] Patching kotlin/Result.$name$descriptor")
                                // Normalise: dex2jar sometimes emits '_impl' instead of '-impl'
                                val base = name.removeSuffix("_impl").removeSuffix("-impl")
                                when (base) {
                                    // (Object) → Object  identity — just leave value on stack
                                    "constructor" -> { /* no-op, value already on stack */ }

                                    // (Object) → boolean
                                    "isSuccess" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "isSuccess", "(Ljava/lang/Object;)Z", false,
                                    )
                                    "isFailure" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "isFailure", "(Ljava/lang/Object;)Z", false,
                                    )

                                    // (Object) → Throwable
                                    "exceptionOrNull" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "exceptionOrNull", "(Ljava/lang/Object;)Ljava/lang/Throwable;", false,
                                    )

                                    // (Object) → Object  (success value or null)
                                    "getOrNull", "getValue" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "getOrNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false,
                                    )

                                    // (Object) → void  (throws if failure)
                                    "throwOnFailure" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "throwOnFailure", "(Ljava/lang/Object;)V", false,
                                    )

                                    // (Object) → Object  wrap/unwrap
                                    "box" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "box", "(Ljava/lang/Object;)Ljava/lang/Object;", false,
                                    )
                                    "unbox" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "unbox", "(Ljava/lang/Object;)Ljava/lang/Object;", false,
                                    )

                                    // (Object) → String
                                    "toString" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "toStringImpl", "(Ljava/lang/Object;)Ljava/lang/String;", false,
                                    )

                                    // (Object) → int
                                    "hashCode" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "hashCodeImpl", "(Ljava/lang/Object;)I", false,
                                    )

                                    // (Object, Object) → boolean
                                    "equals" -> super.visitMethodInsn(
                                        org.objectweb.asm.Opcodes.INVOKESTATIC, COMPAT,
                                        "equalsImpl", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false,
                                    )

                                    else -> {
                                        // Unknown *_impl — route through generic compat handler
                                        // so it fails with a clear error rather than a cryptic
                                        // NoSuchMethodError pointing into the JVM internals.
                                        println("[ASMClassLoader] Unknown kotlin/Result synthetic: $name$descriptor — passing through")
                                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                                    }
                                }
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                            }
                        }
                    }
                }
            }
            reader.accept(visitor, org.objectweb.asm.ClassReader.EXPAND_FRAMES)
            writer.toByteArray()
        } catch (e: Throwable) {
            println("[ASMClassLoader] Bytecode patching failed, using original bytes: ${e.message}")
            bytes
        }
    }
}
