package eu.kanade.tachiyomi.extension

import android.content.Context
import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
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
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val version: String,
    val nsfw: Int,
    val sources: List<SourceInfo>
)

@Serializable
data class SourceInfo(
    val name: String,
    val lang: String,
    val id: String,
    val baseUrl: String
)

object ExtensionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { Injekt.get<NetworkHelper>().client }

    val extensionsDir = File(System.getProperty("user.home"), "AppData/Local/AniyomiDesktop/extensions")
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
            apkFile.inputStream().use { stream ->
                val reader = MultiDexFileReader.open(stream)
                Dex2jar.from(reader).to(jarFile.toPath())
            }
        } catch (e: Exception) {
            throw RuntimeException("Error al traducir DEX a JAR: ${e.message}", e)
        }
    }

    // Loads the JAR and returns the list of instantiated AnimeSources
    fun loadExtension(jarFile: File): List<AnimeSource> {
        val urls = arrayOf(jarFile.toURI().toURL())
        val parentClassLoader = this.javaClass.classLoader
        val classLoader = ASMClassLoader(urls, parentClassLoader)
        val loadedSources = mutableListOf<AnimeSource>()

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
                            val instance = clazz.getDeclaredConstructor().newInstance() as AnimeSource
                            loadedSources.add(instance)
                        } else if (AnimeSourceFactory::class.java.isAssignableFrom(clazz)) {
                            val factoryInstance = clazz.getDeclaredConstructor().newInstance() as AnimeSourceFactory
                            loadedSources.addAll(factoryInstance.createSources())
                        }
                    } catch (e: Throwable) {
                        println("[LOAD_EXT_ERR] Error al cargar clase $className: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        return loadedSources
    }

    // High level method to install an extension: downloads APK, translates to JAR, loads sources, and returns them
    suspend fun installExtension(repoUrl: String, extension: ExtensionInfo): List<AnimeSource> {
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
    fun loadLocalExtensions(): List<AnimeSource> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".jar") } ?: return emptyList()
        val allSources = mutableListOf<AnimeSource>()
        for (file in files) {
            try {
                allSources.addAll(loadExtension(file))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return allSources
    }
}

class ASMClassLoader(urls: Array<java.net.URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Only intercept extension classes
        if (name.startsWith("eu.kanade.tachiyomi.animeextension") || name.startsWith("eu.kanade.tachiyomi.extension")) {
            val path = name.replace('.', '/') + ".class"
            val res = findResource(path)
            if (res != null) {
                try {
                    res.openStream().use { stream ->
                        val originalBytes = stream.readBytes()
                        val fixedBytes = fixStackFrames(originalBytes)
                        val clazz = defineClass(name, fixedBytes, 0, fixedBytes.size)
                        if (resolve) {
                            resolveClass(clazz)
                        }
                        return clazz
                    }
                } catch (e: Throwable) {
                    println("[ASMClassLoader] Error fixing frames for $name: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
        return super.loadClass(name, resolve)
    }

    private fun fixStackFrames(classBytes: ByteArray): ByteArray {
        val cr = org.objectweb.asm.ClassReader(classBytes)
        val cw = object : org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES or org.objectweb.asm.ClassWriter.COMPUTE_MAXS) {
            override fun getCommonSuperClass(type1: String, type2: String): String {
                return try {
                    val class1 = Class.forName(type1.replace('/', '.'), false, this@ASMClassLoader)
                    val class2 = Class.forName(type2.replace('/', '.'), false, this@ASMClassLoader)
                    if (class1.isAssignableFrom(class2)) {
                        return type1
                    }
                    if (class2.isAssignableFrom(class1)) {
                        return type2
                    }
                    if (class1.isInterface || class2.isInterface) {
                        return "java/lang/Object"
                    }
                    var c = class1
                    do {
                        c = c.superclass ?: return "java/lang/Object"
                    } while (!c.isAssignableFrom(class2))
                    c.name.replace('.', '/')
                } catch (e: Throwable) {
                    "java/lang/Object"
                }
            }
        }
        cr.accept(cw, 0)
        return cw.toByteArray()
    }
}
