package com.fibelatti.photowidget.widget

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.fibelatti.photowidget.model.LegacyPhotoWidgetLoopingInterval
import com.fibelatti.photowidget.model.LocalPhoto
import com.fibelatti.photowidget.model.PhotoWidget
import com.fibelatti.photowidget.model.PhotoWidgetAspectRatio
import com.fibelatti.photowidget.model.PhotoWidgetLoopingInterval
import com.fibelatti.photowidget.model.PhotoWidgetLoopingInterval.Companion.toLoopingInterval
import com.fibelatti.photowidget.model.PhotoWidgetSource
import com.fibelatti.photowidget.model.PhotoWidgetTapAction
import com.fibelatti.photowidget.platform.PhotoDecoder
import com.fibelatti.photowidget.platform.enumValueOfOrNull
import com.fibelatti.photowidget.preferences.UserPreferencesStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoWidgetStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photoWidgetOrderDao: PhotoWidgetOrderDao,
    private val decoder: PhotoDecoder,
    private val userPreferencesStorage: UserPreferencesStorage,
) {

    private val rootDir by lazy {
        File("${context.filesDir}/widgets").apply {
            mkdirs()
        }
    }

    private val sharedPreferences = context.getSharedPreferences(
        SHARED_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val contentResolver = context.contentResolver

    fun saveWidgetSource(appWidgetId: Int, source: PhotoWidgetSource) {
        sharedPreferences.edit {
            putString("${PreferencePrefix.SOURCE}$appWidgetId", source.name)
        }
    }

    fun getWidgetSource(appWidgetId: Int): PhotoWidgetSource {
        val name = sharedPreferences.getString("${PreferencePrefix.SOURCE}$appWidgetId", null)

        return enumValueOfOrNull<PhotoWidgetSource>(name) ?: userPreferencesStorage.defaultSource
    }

    fun saveWidgetSyncedDir(appWidgetId: Int, dirUri: Set<Uri>) {
        val newDir = dirUri.minus(contentResolver.persistedUriPermissions.map { it.uri }.toSet())
        for (dir in newDir) {
            contentResolver.takePersistableUriPermission(dir, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        sharedPreferences.edit {
            putStringSet("${PreferencePrefix.SYNCED_DIR}$appWidgetId", dirUri.map { it.toString() }.toSet())
        }
    }

    fun removeSyncedDir(appWidgetId: Int, dirUri: Uri) {
        contentResolver.releasePersistableUriPermission(dirUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val currentDir = getWidgetSyncDir(appWidgetId = appWidgetId)
        saveWidgetSyncedDir(appWidgetId = appWidgetId, dirUri = currentDir - dirUri)
    }

    fun getWidgetSyncDir(appWidgetId: Int): Set<Uri> {
        val legacyUriString = sharedPreferences.getString("${PreferencePrefix.LEGACY_SYNCED_DIR}$appWidgetId", null)
            ?.let(Uri::parse)

        if (legacyUriString != null) {
            saveWidgetSyncedDir(appWidgetId = appWidgetId, setOf(legacyUriString))
            sharedPreferences.edit { remove("${PreferencePrefix.LEGACY_SYNCED_DIR}$appWidgetId") }
        }

        return sharedPreferences.getStringSet("${PreferencePrefix.SYNCED_DIR}$appWidgetId", null)
            .orEmpty()
            .map(Uri::parse)
            .toSet()
    }

    suspend fun newWidgetPhoto(
        appWidgetId: Int,
        source: Uri,
    ): LocalPhoto? = withContext(Dispatchers.IO) {
        Timber.d("New widget photo: $source (appWidgetId=$appWidgetId)")
        val widgetDir = getWidgetDir(appWidgetId = appWidgetId)
        val originalPhotosDir = File("$widgetDir/original").apply { mkdirs() }
        val newPhotoName = "${UUID.randomUUID()}.png"

        val originalPhoto = File("$originalPhotosDir/$newPhotoName")
        val croppedPhoto = File("$widgetDir/$newPhotoName")

        val newFiles = listOf(originalPhoto, croppedPhoto)

        runCatching {
            decoder.decode(data = source, maxDimension = PhotoWidget.MAX_STORAGE_DIMENSION)?.let { importedPhoto ->
                newFiles.map { file ->
                    file.createNewFile()

                    async {
                        FileOutputStream(file).use { fos ->
                            importedPhoto.compress(Bitmap.CompressFormat.PNG, 100, fos)
                            Timber.d("$source saved to $file")
                        }
                    }
                }.awaitAll()
            } ?: return@withContext null // Exit early if the bitmap can't be decoded

            // Safety check to ensure the photos were copied correctly
            return@withContext if (newFiles.all { it.exists() }) {
                LocalPhoto(
                    name = newPhotoName,
                    path = croppedPhoto.path,
                )
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun isValidDir(dirUri: Uri): Boolean {
        Timber.d("Checking validity of selected dir: $dirUri")

        if (dirUri.toString().endsWith("DCIM%2FCamera", ignoreCase = true)) return false

        return getDirectoryPhotoCount(dirUri = dirUri) <= 1_000
    }

    suspend fun getWidgetPhotoCount(appWidgetId: Int): Int = withContext(Dispatchers.IO) {
        if (PhotoWidgetSource.DIRECTORY == getWidgetSource(appWidgetId = appWidgetId)) {
            coroutineScope {
                getWidgetSyncDir(appWidgetId = appWidgetId)
                    .map { uri -> async { getDirectoryPhotoCount(dirUri = uri) } }
                    .awaitAll()
                    .sum()
            }
        } else {
            getWidgetDir(appWidgetId = appWidgetId).list { _, name -> name != "original" }?.size ?: 0
        }
    }

    private suspend fun getDirectoryPhotoCount(dirUri: Uri): Int {
        return withDirectoryPhotosCursor(dirUri = dirUri) { _, cursor ->
            var count = 0

            while (cursor.moveToNext()) {
                val mimeType = cursor.getString(1)
                if (mimeType in ALLOWED_TYPES) count += 1
            }

            count
        } ?: 0
    }

    suspend fun getWidgetPhotos(appWidgetId: Int): List<LocalPhoto> = withContext(Dispatchers.IO) {
        Timber.d("Retrieving photos (appWidgetId=$appWidgetId)")

        val croppedPhotos = getWidgetDir(appWidgetId = appWidgetId).let { dir ->
            dir.list { _, name -> name != "original" }
                .orEmpty()
                .map { file -> LocalPhoto(name = file, path = "$dir/$file") }
        }
        val dict: Map<String, LocalPhoto> = croppedPhotos.associateBy { it.name }

        Timber.d("Cropped photos found: ${dict.size}")

        val source = getWidgetSource(appWidgetId = appWidgetId).also {
            Timber.d("Widget source: $it")
        }

        return@withContext if (PhotoWidgetSource.DIRECTORY == source) {
            getDirectoryPhotos(appWidgetId = appWidgetId, croppedPhotos = dict)
        } else {
            getWidgetOrder(appWidgetId = appWidgetId)
                .ifEmpty { dict.keys }
                .mapNotNull { dict[it] }
        }.also { Timber.d("Total photos found: ${it.size}") }
    }

    private suspend fun getDirectoryPhotos(
        appWidgetId: Int,
        croppedPhotos: Map<String, LocalPhoto>,
    ): List<LocalPhoto> = coroutineScope {
        getWidgetSyncDir(appWidgetId = appWidgetId)
            .map { uri ->
                async {
                    withDirectoryPhotosCursor(dirUri = uri) { documentUri, cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val documentId = cursor.getString(0)
                                val mimeType = cursor.getString(1)
                                val documentName = cursor.getString(2).takeUnless { it.startsWith(".trashed") }
                                val documentLastModified = cursor.getLong(3)

                                val fileUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, documentId)

                                if (documentName != null && mimeType in ALLOWED_TYPES && fileUri != null) {
                                    val localPhoto = LocalPhoto(
                                        name = documentName,
                                        path = croppedPhotos[documentName]?.path,
                                        externalUri = fileUri,
                                        timestamp = documentLastModified,
                                    )

                                    add(localPhoto)
                                }
                            }
                        }.sortedByDescending { it.timestamp }
                    }.orEmpty()
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend inline fun <T> withDirectoryPhotosCursor(
        dirUri: Uri,
        crossinline block: (documentUri: Uri, Cursor) -> T,
    ): T? = withContext(Dispatchers.IO) {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            /* treeUri = */ dirUri,
            /* documentId = */ DocumentsContract.getTreeDocumentId(dirUri),
        )

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            /* treeUri = */ documentUri,
            /* parentDocumentId = */ DocumentsContract.getDocumentId(documentUri),
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        contentResolver.query(
            /* uri = */ childrenUri,
            /* projection = */ projection,
            /* selection = */ null,
            /* selectionArgs = */ null,
            /* sortOrder = */ null,
        )?.use { cursor -> block(documentUri, cursor) }
    }

    suspend fun getCropSources(appWidgetId: Int, localPhoto: LocalPhoto): Pair<Uri, Uri> {
        val widgetDir = getWidgetDir(appWidgetId = appWidgetId)
        val croppedPhoto = File("$widgetDir/${localPhoto.name}")

        if (localPhoto.externalUri != null) {
            return localPhoto.externalUri to Uri.fromFile(croppedPhoto)
        } else {
            val originalPhotosDir = File("$widgetDir/original")
            val originalPhoto = File("$originalPhotosDir/${localPhoto.name}")

            if (!originalPhoto.exists()) {
                withContext(Dispatchers.IO) {
                    originalPhoto.createNewFile()

                    FileInputStream(croppedPhoto).use { fileInputStream ->
                        fileInputStream.copyTo(FileOutputStream(originalPhoto))
                    }
                }
            }

            return Uri.fromFile(originalPhoto) to Uri.fromFile(croppedPhoto)
        }
    }

    fun deleteWidgetPhoto(appWidgetId: Int, photoName: String) {
        val widgetDir = getWidgetDir(appWidgetId = appWidgetId)
        val originalPhotosDir = File("$widgetDir/original")

        with(File("$originalPhotosDir/$photoName")) {
            if (exists()) delete()
        }
        with(File("$widgetDir/$photoName")) {
            if (exists()) delete()
        }
    }

    suspend fun saveWidgetOrder(appWidgetId: Int, order: List<String>) {
        photoWidgetOrderDao.replaceWidgetOrder(
            widgetId = appWidgetId,
            order = order.mapIndexed { index, photoId ->
                PhotoWidgetOrderDto(
                    widgetId = appWidgetId,
                    photoIndex = index,
                    photoId = photoId,
                )
            },
        )
    }

    private suspend fun getWidgetOrder(appWidgetId: Int): List<String> {
        // Check for legacy storage value
        val value = sharedPreferences.getString("${PreferencePrefix.ORDER}$appWidgetId", null)
            ?.split(",")

        if (value != null) {
            // Migrate found value to the new storage
            saveWidgetOrder(appWidgetId, value)
            sharedPreferences.edit { remove("${PreferencePrefix.ORDER}$appWidgetId") }
        }

        // Return it to the caller, or retrieve it from the new storage if not found
        return value ?: photoWidgetOrderDao.getWidgetOrder(appWidgetId = appWidgetId)
    }

    fun saveWidgetShuffle(appWidgetId: Int, value: Boolean) {
        sharedPreferences.edit {
            putBoolean("${PreferencePrefix.SHUFFLE}$appWidgetId", value)
        }
    }

    fun getWidgetShuffle(appWidgetId: Int): Boolean {
        return sharedPreferences.getBoolean(
            "${PreferencePrefix.SHUFFLE}$appWidgetId",
            userPreferencesStorage.defaultShuffle,
        )
    }

    fun saveWidgetInterval(appWidgetId: Int, interval: PhotoWidgetLoopingInterval) {
        sharedPreferences.edit {
            remove("${PreferencePrefix.LEGACY_INTERVAL}$appWidgetId")
            putLong("${PreferencePrefix.INTERVAL}$appWidgetId", interval.toMinutes())
        }
    }

    fun getWidgetInterval(appWidgetId: Int): PhotoWidgetLoopingInterval {
        val legacyName = sharedPreferences.getString("${PreferencePrefix.LEGACY_INTERVAL}$appWidgetId", null)
        val legacyValue = enumValueOfOrNull<LegacyPhotoWidgetLoopingInterval>(legacyName)
        val value = sharedPreferences.getLong("${PreferencePrefix.INTERVAL}$appWidgetId", 0)

        return when {
            legacyValue != null -> {
                PhotoWidgetLoopingInterval(
                    repeatInterval = legacyValue.repeatInterval,
                    timeUnit = legacyValue.timeUnit,
                )
            }

            value > 0 -> value.toLoopingInterval()

            else -> userPreferencesStorage.defaultInterval
        }
    }

    fun saveWidgetIntervalEnabled(appWidgetId: Int, value: Boolean) {
        sharedPreferences.edit {
            putBoolean("${PreferencePrefix.INTERVAL_ENABLED}$appWidgetId", value)
        }
    }

    fun getWidgetIntervalEnabled(appWidgetId: Int): Boolean {
        return sharedPreferences.getBoolean(
            "${PreferencePrefix.INTERVAL_ENABLED}$appWidgetId",
            userPreferencesStorage.defaultIntervalEnabled,
        )
    }

    fun saveWidgetIndex(appWidgetId: Int, index: Int) {
        sharedPreferences.edit {
            putInt("${PreferencePrefix.INDEX}$appWidgetId", index)
        }
    }

    fun getWidgetIndex(appWidgetId: Int): Int {
        return sharedPreferences.getInt("${PreferencePrefix.INDEX}$appWidgetId", 0)
    }

    fun saveWidgetPastIndices(appWidgetId: Int, pastIndices: Set<Int>) {
        sharedPreferences.edit {
            putStringSet("${PreferencePrefix.PAST_INDICES}$appWidgetId", pastIndices.map { "$it" }.toSet())
        }
    }

    fun getWidgetPastIndices(appWidgetId: Int): Set<Int> {
        return sharedPreferences.getStringSet("${PreferencePrefix.PAST_INDICES}$appWidgetId", null)
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    fun saveWidgetAspectRatio(appWidgetId: Int, aspectRatio: PhotoWidgetAspectRatio) {
        sharedPreferences.edit {
            putString("${PreferencePrefix.RATIO}$appWidgetId", aspectRatio.name)
        }
    }

    fun getWidgetAspectRatio(appWidgetId: Int): PhotoWidgetAspectRatio {
        val name = sharedPreferences.getString("${PreferencePrefix.RATIO}$appWidgetId", null)

        return enumValueOfOrNull<PhotoWidgetAspectRatio>(name) ?: PhotoWidgetAspectRatio.SQUARE
    }

    fun saveWidgetShapeId(appWidgetId: Int, shapeId: String) {
        sharedPreferences.edit {
            putString("${PreferencePrefix.SHAPE}$appWidgetId", shapeId)
        }
    }

    fun getWidgetShapeId(appWidgetId: Int): String {
        return sharedPreferences.getString("${PreferencePrefix.SHAPE}$appWidgetId", null)
            ?: userPreferencesStorage.defaultShape
    }

    fun saveWidgetCornerRadius(appWidgetId: Int, cornerRadius: Float) {
        sharedPreferences.edit {
            putFloat("${PreferencePrefix.CORNER_RADIUS}$appWidgetId", cornerRadius)
        }
    }

    fun getWidgetCornerRadius(appWidgetId: Int): Float {
        return sharedPreferences.getFloat(
            "${PreferencePrefix.CORNER_RADIUS}$appWidgetId",
            userPreferencesStorage.defaultCornerRadius,
        )
    }

    fun saveWidgetTapAction(appWidgetId: Int, tapAction: PhotoWidgetTapAction) {
        sharedPreferences.edit {
            putString("${PreferencePrefix.TAP_ACTION}$appWidgetId", tapAction.name)
        }
    }

    fun getWidgetTapAction(appWidgetId: Int): PhotoWidgetTapAction {
        val name = sharedPreferences.getString("${PreferencePrefix.TAP_ACTION}$appWidgetId", null)

        return enumValueOfOrNull<PhotoWidgetTapAction>(name) ?: userPreferencesStorage.defaultTapAction
    }

    fun saveWidgetIncreaseBrightness(appWidgetId: Int, value: Boolean) {
        sharedPreferences.edit {
            putBoolean("${PreferencePrefix.INCREASE_BRIGHTNESS}$appWidgetId", value)
        }
    }

    fun getWidgetIncreaseBrightness(appWidgetId: Int): Boolean {
        return sharedPreferences.getBoolean(
            "${PreferencePrefix.INCREASE_BRIGHTNESS}$appWidgetId",
            userPreferencesStorage.defaultIncreaseBrightness,
        )
    }

    fun saveWidgetAppShortcut(appWidgetId: Int, appName: String?) {
        sharedPreferences.edit {
            putString("${PreferencePrefix.APP_SHORTCUT}$appWidgetId", appName)
        }
    }

    fun getWidgetAppShortcut(appWidgetId: Int): String? {
        return sharedPreferences.getString("${PreferencePrefix.APP_SHORTCUT}$appWidgetId", null)
    }

    suspend fun deleteWidgetData(appWidgetId: Int) {
        Timber.d("Deleting data (appWidgetId=$appWidgetId)")
        getWidgetDir(appWidgetId).deleteRecursively()

        sharedPreferences.edit {
            PreferencePrefix.entries.forEach { prefix -> remove("$prefix$appWidgetId") }
        }

        photoWidgetOrderDao.deleteWidgetOrder(appWidgetId = appWidgetId)
    }

    suspend fun deleteUnusedWidgetData(existingWidgetIds: List<Int>) {
        val existingWidgetsAsDirName = existingWidgetIds.map { "$it" }.toSet()
        val unusedWidgetIds = rootDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in existingWidgetsAsDirName }
            .map { it.name.toInt() }

        Timber.d("Deleting temp widget data")
        deleteWidgetData(appWidgetId = 0)

        for (id in unusedWidgetIds) {
            Timber.d("Unused data found (appWidgetId=$id)")
            deleteWidgetData(appWidgetId = id)
        }
    }

    fun renameTemporaryWidgetDir(appWidgetId: Int) {
        val tempDir = File("$rootDir/0")
        if (tempDir.exists()) {
            tempDir.renameTo(File("$rootDir/$appWidgetId"))
        }
    }

    private fun getWidgetDir(appWidgetId: Int): File {
        return File("$rootDir/$appWidgetId").apply {
            mkdirs()
        }
    }

    private enum class PreferencePrefix(val value: String) {
        SOURCE(value = "appwidget_source_"),

        /**
         * Key from when initial support for directory based widgets was introduced.
         */
        LEGACY_SYNCED_DIR(value = "appwidget_synced_dir_"),

        /**
         * Key from when support for syncing multiple directories was introduced.
         */
        SYNCED_DIR(value = "appwidget_synced_dir_set_"),

        ORDER(value = "appwidget_order_"),
        SHUFFLE(value = "appwidget_shuffle_"),

        /**
         * Key from when the interval was persisted as [LegacyPhotoWidgetLoopingInterval].
         */
        LEGACY_INTERVAL(value = "appwidget_interval_"),

        /**
         * Key from when the interval was migrated to [PhotoWidgetLoopingInterval].
         */
        INTERVAL(value = "appwidget_interval_minutes_"),
        INTERVAL_ENABLED(value = "appwidget_interval_enabled_"),
        INDEX(value = "appwidget_index_"),
        PAST_INDICES(value = "appwidget_past_indices_"),
        RATIO(value = "appwidget_aspect_ratio_"),
        SHAPE(value = "appwidget_shape_"),
        CORNER_RADIUS(value = "appwidget_corner_radius_"),
        TAP_ACTION(value = "appwidget_tap_action_"),
        INCREASE_BRIGHTNESS(value = "appwidget_increase_brightness_"),
        APP_SHORTCUT(value = "appwidget_app_shortcut_"),
        ;

        override fun toString(): String = value
    }

    private companion object {

        const val SHARED_PREFERENCES_NAME = "com.fibelatti.photowidget.PhotoWidget"

        val ALLOWED_TYPES = arrayOf("image/jpeg", "image/png")
    }
}
