package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.sync.ProgressClock
import eu.kanade.tachiyomi.data.sync.ProgressSyncManager
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import mihon.core.common.archive.ArchivePasswordException
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.takeBytes
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.MAX_FILE_NAME_BYTES
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import exh.source.isEhBasedManga
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.BookmarkItem
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterMemo
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.BookmarkRepository
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Komiho 诊断：自动条漫切换 / viewer 重建的日志 tag（与条漫 holder 的 `Waifu2xWebtoon` 同名，
 * 一起 grep 即可看到「检测命中 → 重建 viewer → 页面重算」的完整时序）。
 */
private const val KOMIHA_AUTOWEBTOON_TAG = "Waifu2xWebtoon"

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val tempFileManager: UniFileTempFileManager = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    // SY -->
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val getMergedReferencesById: GetMergedReferencesById = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    // SY -->
    private val bookmarkRepository: BookmarkRepository = Injekt.get(),
    // SY <--
) : ViewModel() {

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    // SY --> Komiho: 加密本密码输入：暂存待重载章节与起始页
    private var archivePasswordChapter: ReaderChapter? = null
    private var archivePasswordPage: Int? = null
    // SY <--

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    // SY -->
    /**
     * Lightweight progress channel. Opening a chapter only needs the page another device left off
     * at, which does not justify a whole backup + upload + restore round trip.
     */
    private val progressSyncManager: ProgressSyncManager by lazy {
        ProgressSyncManager(Injekt.get<Application>())
    }
    // SY <--

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking { getChaptersByMangaId.await(manga.id, applyScanlatorFilter = false) }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        // SY -->
        val (chapters, mangaMap) = runBlocking {
            if (manga.source == MERGED_SOURCE_ID) {
                getMergedChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) to
                    getMergedMangaById.await(manga.id)
                        .associateBy { it.id }
            } else {
                getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true) to null
            }
        }
        fun isChapterDownloaded(chapter: Chapter): Boolean {
            val chapterManga = mangaMap?.get(chapter.mangaId) ?: manga
            return downloadManager.isChapterDownloaded(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = chapterManga.ogTitle,
                sourceId = chapterManga.source,
            )
        }
        // SY <--

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead.get() || readerPreferences.skipFiltered.get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead.get() && it.read -> true
                        readerPreferences.skipFiltered.get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                // SY -->
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !isChapterDownloaded(it)
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        isChapterDownloaded(it)
                                    ) ||
                                // SY <--
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloaded(manga, mangaMap)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    private val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading.get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            // SY -->
            .drop(1) // allow the loader to set the first page and chapter id
            // SY <-
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        // SY -->
        state.mapLatest { it.ehAutoscrollFreq }
            .distinctUntilChanged()
            .drop(1)
            .onEach { text ->
                val parsed = text.toDoubleOrNull()

                if (parsed == null || parsed <= 0 || parsed > 9999) {
                    readerPreferences.autoscrollInterval.set(-1f)
                    mutableState.update { it.copy(isAutoScrollEnabled = false) }
                } else {
                    readerPreferences.autoscrollInterval.set(parsed.toFloat())
                    mutableState.update { it.copy(isAutoScrollEnabled = true) }
                }
            }
            .launchIn(viewModelScope)
        // SY <--
    }

    override fun onCleared() {
        // SY -->
        // Persist the reading clock: viewModelScope is already cancelled here, so this has to be a
        // plain call, and a process killed right after leaving the reader must not lose the stamps
        // produced while reading.
        runCatching { ProgressClock.flush() }
        // Leaving the reader is what "sync after reading" means: run the full sync once nobody is
        // waiting on the reader any more. WorkManager only needs a Context, which matters because
        // viewModelScope has already been cancelled by the time onCleared runs.
        if (syncPreferences.isSyncEnabled() && syncPreferences.getSyncTriggerOptions().syncOnChapterRead) {
            SyncDataJob.startNow(Injekt.get<Application>())
        }
        // SY <--

        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        deletePendingChapters()
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long /* SY --> */, page: Int?/* SY <-- */): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    // SY -->
                    sourceManager.isInitialized.first { it }
                    val source = sourceManager.getOrStub(manga.source)
                    val metadataSource = source.getMainSource<MetadataSource<*, *>>()
                    val metadata = if (metadataSource != null) {
                        getFlatMetadataById.await(mangaId)?.raise(metadataSource.metaClass)
                    } else {
                        null
                    }
                    val mergedReferences = if (source is MergedSource) {
                        runBlocking {
                            getMergedReferencesById.await(manga.id)
                        }
                    } else {
                        emptyList()
                    }
                    val mergedManga = if (source is MergedSource) {
                        runBlocking {
                            getMergedMangaById.await(manga.id)
                        }.associateBy { it.id }
                    } else {
                        emptyMap()
                    }
                    val relativeTime = uiPreferences.relativeTime.get()
                    val autoScrollFreq = readerPreferences.autoscrollInterval.get()
                    // SY <--
                    mutableState.update {
                        it.copy(
                            manga = manga,
                            /* SY --> */
                            meta = metadata,
                            mergedManga = mergedManga,
                            dateRelativeTime = relativeTime,
                            ehAutoscrollFreq = if (autoScrollFreq == -1f) {
                                ""
                            } else {
                                autoScrollFreq.toString()
                            },
                            isAutoScrollEnabled = autoScrollFreq != -1f,
                            /* SY <-- */
                        )
                    }
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    // val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(
                        context = context,
                        downloadManager = downloadManager,
                        downloadProvider = downloadProvider,
                        manga = manga,
                        source = source, /* SY --> */
                        sourceManager = sourceManager,
                        readerPrefs = readerPreferences,
                        mergedReferences = mergedReferences,
                        mergedManga = mergedManga, /* SY <-- */
                    )

                    loadChapter(
                        loader!!,
                        chapterList.first { chapterId == it.chapter.id },
                        /* SY --> */page, /* SY <-- */
                    )
                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                // SY --> 加密本缺密码：暂存待重载章节，交上层弹密码框（不当致命错误）
                if (e is ArchivePasswordException) {
                    archivePasswordChapter = chapterList.firstOrNull { chapterId == it.chapter.id }
                    archivePasswordPage = page
                }
                // SY <--
                Result.failure(e)
            }
        }
    }

    // SY -->
    fun getChapters(): List<ReaderChapterItem> {
        val currentChapter = getCurrentChapter()

        return chapterList.map {
            ReaderChapterItem(
                chapter = it.chapter.toDomainChapter()!!,
                manga = manga!!,
                isCurrent = it.chapter.id == currentChapter?.chapter?.id,
                dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat.get()),
            )
        }
    }
    // SY <--

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        // SY -->
        page: Int? = null,
        // SY <--
    ): ViewerChapters {
        // SY -->
        // Before the chapter is loaded, two things happen:
        //  1. the previous chapter's progress is flushed to the lightweight channel (forced,
        //     because leaving a chapter is exactly when its last page matters);
        //  2. unless a specific page was requested, the page another device left off at is pulled
        //     in and applied, so the reader opens where it was last read anywhere.
        getCurrentChapter()?.let { current ->
            current.chapter.id?.let { id ->
                viewModelScope.launchNonCancellable {
                    progressSyncManager.recordLocalProgress(id, force = true)
                }
            }
        }

        if (page == null) {
            manga?.let { currentManga ->
                progressSyncManager.applyRemoteProgress(currentManga.id, chapter.chapter.url)?.let { applied ->
                    chapter.chapter.last_page_read = applied.lastPageRead.toInt()
                    chapter.chapter.read = applied.read
                    chapter.requestedPage = applied.lastPageRead.toInt()
                }
            }
        }
        // SY <--

        loader.loadChapter(chapter /* SY --> */, page/* SY <-- */)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    currentPageBookmarked = false,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()

            try {
                loadChapter(loader, chapter)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun loadNewChapterFromDialog(chapter: Chapter) {
        viewModelScope.launchIO {
            val newChapter = chapterList.firstOrNull { it.chapter.id == chapter.id } ?: return@launchIO
            loadAdjacent(newChapter)
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        if (chapter.pageLoader?.isLocal == false) {
            val manga = manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                /* SY --> */ manga.ogTitle /* SY <-- */,
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage, currentPageText: String /* SY --> */, hasExtraPage: Boolean /* SY <-- */) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        // SY -->
        mutableState.update { it.copy(currentPageText = currentPageText) }
        // SY <--

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // MihonSY: auto-detect webtoons by image aspect ratio (webtoons are tall strips)
        maybeAutoWebtoonByAspectRatio(page)

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page/* SY --> */, hasExtraPage/* SY <-- */)
        }

        // SY --> Komiho: 翻页后刷新「当前页是否已加书签」图标状态。
        viewModelScope.launchNonCancellable {
            selectedChapter.chapter.id?.let { cid ->
                val marked = bookmarkRepository.isPageBookmarked(cid, page.index)
                mutableState.update { it.copy(currentPageBookmarked = marked) }
            }
        }
        // SY <--

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        eventChannel.trySend(Event.PageChanged)
    }

    // MihonSY -->
    /** Set once the aspect-ratio check has finished (either switched or gave up). */
    @Volatile
    private var autoWebtoonAspectDone = false

    /**
     * MihonSY: 比例检测命中的章节 URL——自动条漫只对「这一章」内存生效，
     * 绝不写入 manga.readingMode（持久记忆只由用户手动切换模式更新）。
     * 换章后本章标记失效；新章仍会重新检测，但本书若已被判为条漫则直接继承
     * （见 [autoWebtoonMangaId]），同系列混排条漫/页漫仍可按章复核退回。
     */
    @Volatile
    private var autoWebtoonEffectiveChapter: String? = null

    /**
     * Komiho:「这本书是条漫」的**会话内**记忆（不写 manga.readingMode）。
     *
     * 任意一章被比例探测命中后记下本书 id，之后**其余章节直接按条漫解析**
     * （见 [getMangaReadingMode]）—— 不再逐章重判，也就不再每章翻转一次 viewer
     * 并顺带弹一次模式提示。复核仍保留：新章开头几页全部量完且都不是长条时撤销这条记忆、
     * 退回页漫（见 [revertAutoWebtoonMangaMemoryIfNeeded]），混排系列不会被粘死。
     */
    @Volatile
    private var autoWebtoonMangaId: Long? = null

    /** Chapter URL the current check session belongs to; reset on chapter change. */
    private var autoWebtoonCheckChapter: String? = null

    /** Indices (0-based) of early pages in the current chapter already measured as non-tall. */
    private val autoWebtoonCheckedIndices: MutableSet<Int> = Collections.synchronizedSet(HashSet())

    /**
     * How many delayed re-checks remain for the current chapter. Each re-check
     * gives still-downloading early pages a chance to be measured so the switch
     * happens without requiring the user to scroll first.
     */
    private var autoWebtoonRetriesLeft = 0

    /**
     * Heuristic auto-webtoon by image aspect ratio.
     *
     * Webtoon chapters are (almost) always long strips, but a chapter may open with a cover
     * page (normal manga ratio ~1.5) before the long strips start. Instead of inspecting only
     * the first page, we inspect the first [AUTO_WEBTOON_PAGES_TO_CHECK] pages as the reader
     * passes through them: if ANY of them is a tall strip (ratio above
     * [AUTO_WEBTOON_MIN_ASPECT_RATIO]) the chapter is a webtoon and the reader switches to
     * webtoon mode (in-memory; the saved manga mode is never touched). 命中后同时记下
     * 「这本书是条漫」（[autoWebtoonMangaId]），本书其余章节直接按条漫起、免逐章翻转。
     * Only when all of them turn out normal-sized does the check give up（那时若本条记忆存在会撤销）。
     *
     * Triggered from TWO places (MihonSY):
     *  1. [onPageSelected] — fallback, fires on page turns.
     *  2. [ReaderActivity.onPageLoaded] — a page's image finished decoding, so the
     *     aspect-ratio check runs the moment a strip is Ready, without waiting for the
     *     user to scroll to it.
     *
     * Complements the tag/source based detection in [getMangaReadingMode].
     */
    internal fun maybeAutoWebtoonByAspectRatio(page: ReaderPage) {
        if (autoWebtoonAspectDone) return
        if (!readerPreferences.useAutoWebtoon.get()) return
        val manga = manga ?: return
        // Only when the manga has no explicit reading mode (still DEFAULT).
        // MihonSY: 非 DEFAULT 只剩用户手动记忆——手动选择优先，自动检测永不覆盖。
        if (ReadingMode.fromPreference(manga.readingMode.toInt()) != ReadingMode.DEFAULT) return
        // MihonSY: 只检测当前激活章节——预加载章的就绪页面不触发，
        // 避免读到上一章时被下一章的预载结果提前重建 viewer。
        if (page.chapter != getCurrentChapter()) return
        // Skip if tag/source based detection already resolved to webtoon
        // Komiho: 「本书是条漫」的会话记忆**不**在此跳过 —— 继承来的章节仍要复核，
        // 量完发现开头几页都不是长条就撤销记忆退回页漫（见本函数里 conclude 的调用处）。
        if (isWebtoonByTagOrDefault(manga)) return

        val chapterPages = page.chapter.pages ?: return
        val checkCount = minOf(AUTO_WEBTOON_PAGES_TO_CHECK, chapterPages.size)

        // A new chapter starts a fresh check window.
        val chapterUrl = page.chapter.chapter.url
        if (autoWebtoonCheckChapter != chapterUrl) {
            synchronized(autoWebtoonCheckedIndices) { autoWebtoonCheckedIndices.clear() }
            autoWebtoonCheckChapter = chapterUrl
            // Fresh window: allow a few delayed re-checks for still-loading pages.
            autoWebtoonRetriesLeft = AUTO_WEBTOON_RETRIES
            // MihonSY: 检测窗口按章独立——新章重开窗口（旧实现 done 后永不复位，
            // 同一会话里第一章放弃检测后，后续条漫章节永远不再判断）。
            autoWebtoonAspectDone = false
            // MihonSY: 上一章的自动条漫只对上一章生效——进入新章时若解析出的
            // 模式不同（如条漫章 → 页漫章），立即按新章模式重建 viewer。
            val previousMode = getMangaReadingMode()
            if (autoWebtoonEffectiveChapter != null && autoWebtoonEffectiveChapter != chapterUrl) {
                autoWebtoonEffectiveChapter = null
                if (getMangaReadingMode() != previousMode) {
                    logcat { "MihonSY auto-webtoon: leaving auto-webtoon chapter, rebuilding viewer for new chapter" }
                    recreateViewerForAutoMode()
                }
            }
        }

        // Check EVERY early page that is already ready, not just the current one.
        // A webtoon may open with a horizontal cover before the tall strips start;
        // measuring only the cover would defer the switch until the user scrolls.
        // Pages still queued/downloading are skipped here - they will be picked up
        // when they become ready and onPageSelected fires again.
        val readyPages = chapterPages
            .take(checkCount)
            .filter { it.status == Page.State.Ready && it.stream != null }
        if (readyPages.isEmpty()) return

        viewModelScope.launchIO {
            val hit = probeAutoWebtoonPages(readyPages)
            if (hit != null) {
                logcat { "MihonSY auto-webtoon: page ${hit.number} is a tall strip, switching to webtoon mode" }
                applyAutoWebtoonForCurrentChapter(chapterUrl)
                return@launchIO
            }
            if (concludeAutoWebtoonCheckIfAllEarlyChecked(checkCount)) {
                // Komiho: 复核不过 ⇒ 撤销「本书是条漫」的会话记忆并退回页漫（混排系列）。
                revertAutoWebtoonMangaMemoryIfNeeded()
                return@launchIO
            }
            if (autoWebtoonRetriesLeft > 0) {
                // Some early pages are still downloading. Retry shortly so a strip that
                // becomes ready after the cover (without the user scrolling) is still
                // detected and the reader switches to webtoon mode immediately.
                autoWebtoonRetriesLeft--
                viewModelScope.launchIO {
                    delay(700)
                    if (!autoWebtoonAspectDone) {
                        val firstPage = page.chapter.pages?.firstOrNull() ?: return@launchIO
                        maybeAutoWebtoonByAspectRatio(firstPage)
                    }
                }
            }
        }
    }

    /**
     * Komiho (2026-09-24): 首次把章节喂给 viewer **之前**的提前判定。
     *
     * 本地目录 / 归档 / SMB·WebDAV 散图源在列页时（`PageLoader.getPages()`）就把所有页置
     * `Page.State.Ready`，而判定只需读图片头（[measurePageAspectRatio] 用 `inJustDecodeBounds`），
     * 所以模式可以在 viewer 拿到第一页之前就定下来 —— 避免「先按页漫建起来、再切换重建」，
     * 那条路径会把可见页白白解码一遍。
     *
     * 只探测**已经 Ready** 的页面，绝不等待：在线源的页面 Ready 由 holder 拉取驱动，不喂章节就没人
     * 下载，在这里等会死锁；所以没有 Ready 页时立即返回。探测有 [PRE_RESOLVE_BUDGET_MS] 预算，
     * 超预算就把剩下的交给兜底路径（[maybeAutoWebtoonByAspectRatio]），不拖首屏。
     *
     * @return true 表示模式已改，调用方应**先重建 viewer 再喂章节**（此刻 viewer 手里一页都没有，
     *         重建是零成本的）。
     */
    internal suspend fun preResolveAutoWebtoon(chapter: ReaderChapter): Boolean {
        if (!readerPreferences.useAutoWebtoon.get()) return false
        val manga = manga ?: return false
        // 只对「没有显式模式」的书生效：手动选择的模式优先，自动检测永不覆盖。
        if (ReadingMode.fromPreference(manga.readingMode.toInt()) != ReadingMode.DEFAULT) return false
        // 标签/来源推断、或全局默认就是条漫（Komga 的 readingDirection、tags 里的 webtoon/long strip）
        // ⇒ 无需再探，也不该被复核撤销。
        if (isWebtoonByTagOrDefault(manga)) return false
        // 本章的探测已经命中过 ⇒ 已定案。
        if (autoWebtoonEffectiveChapter == chapter.chapter.url) return false
        val chapterPages = chapter.pages ?: return false
        val chapterUrl = chapter.chapter.url
        if (autoWebtoonCheckChapter != chapterUrl) {
            // 新章：重开检测窗口。与 [maybeAutoWebtoonByAspectRatio] 里的窗口重开口径一致，
            // 但不重复「离开条漫章就重建」那一步 —— 新章 URL 与旧 effectiveChapter 不同，
            // getMangaReadingMode() 本就已经回落到默认，那一步不会生效。
            synchronized(autoWebtoonCheckedIndices) { autoWebtoonCheckedIndices.clear() }
            autoWebtoonCheckChapter = chapterUrl
            autoWebtoonRetriesLeft = AUTO_WEBTOON_RETRIES
            autoWebtoonAspectDone = false
        } else if (autoWebtoonAspectDone) {
            return false
        }

        val checkCount = minOf(AUTO_WEBTOON_PAGES_TO_CHECK, chapterPages.size)
        val readyPages = chapterPages
            .take(checkCount)
            .filter { it.status == Page.State.Ready && it.stream != null }
        if (readyPages.isEmpty()) return false

        return withIOContext {
            val hit = probeAutoWebtoonPages(
                readyPages = readyPages,
                deadlineMillis = System.currentTimeMillis() + PRE_RESOLVE_BUDGET_MS,
            )
            if (hit != null) {
                // Komiho 诊断：这条决策要能在 logcat 里看见（logcat{} 走 XLog，不进 logcat），
                // 否则排查「第一帧用了哪种 viewer」时只能靠猜。
                android.util.Log.d(
                    KOMIHA_AUTOWEBTOON_TAG,
                    "pre-resolve: page ${hit.number} is a tall strip -> webtoon before first feed",
                )
                logcat {
                    "MihonSY auto-webtoon: page ${hit.number} is a tall strip, " +
                        "switching to webtoon mode (before first feed)"
                }
                markAutoWebtoonForChapter(chapterUrl)
            } else if (concludeAutoWebtoonCheckIfAllEarlyChecked(checkCount)) {
                android.util.Log.d(
                    KOMIHA_AUTOWEBTOON_TAG,
                    "pre-resolve: none of the first $checkCount pages is tall -> keep page mode",
                )
                // Komiho: 头几页都不是长条 ⇒ 撤销「本书是条漫」的记忆；若模式因此变了，
                // 返回 true 让调用方**在喂章节前**重建（此刻 viewer 手里还没有页，重建零成本）。
                revertAutoWebtoonMangaMemoryIfNeeded(rebuild = false)
            } else {
                // 判定不了（预算用尽 / 早期页还没量完）：保留「本书是条漫」的记忆即可，无需重建。
                false
            }
        }
    }

    /**
     * Komiho: 逐个探测 [readyPages] 的图片比例（只读图片头，不解码像素）。命中长条返回那一页；
     * 未命中的页记入 [autoWebtoonCheckedIndices]，已量过的页跳过。
     *
     * [deadlineMillis] 给「首次喂章节前」的判定设预算：超预算立刻返回 null，剩下的交给兜底路径。
     * 必须在 IO 上下文调用（会读磁盘 / 网络）。
     */
    private fun probeAutoWebtoonPages(
        readyPages: List<ReaderPage>,
        deadlineMillis: Long = Long.MAX_VALUE,
    ): ReaderPage? {
        for (candidate in readyPages) {
            if (System.currentTimeMillis() > deadlineMillis) return null
            if (synchronized(autoWebtoonCheckedIndices) { autoWebtoonCheckedIndices.contains(candidate.index) }) {
                continue
            }
            val ratio = measurePageAspectRatio(candidate) ?: continue
            logcat { "MihonSY auto-webtoon aspect check page ${candidate.number}: ratio=$ratio" }
            if (ratio > AUTO_WEBTOON_MIN_ASPECT_RATIO) return candidate
            synchronized(autoWebtoonCheckedIndices) { autoWebtoonCheckedIndices.add(candidate.index) }
        }
        return null
    }

    /** 早期页全部量过且都不是长条 ⇒ 本章判定结束（放弃自动条漫）。返回是否就此结束。 */
    private fun concludeAutoWebtoonCheckIfAllEarlyChecked(checkCount: Int): Boolean {
        val allEarlyChecked = synchronized(autoWebtoonCheckedIndices) {
            (0 until checkCount).all { autoWebtoonCheckedIndices.contains(it) }
        }
        if (allEarlyChecked) {
            autoWebtoonAspectDone = true
            logcat { "MihonSY auto-webtoon: none of the first $checkCount pages is tall, giving up" }
        }
        return allEarlyChecked
    }

    /**
     * 把 [chapterUrl] 记为「本章自动条漫」，并把本书记为「会话内条漫」（[autoWebtoonMangaId]，
     * 后续章节免重判），返回**模式是否真的变了**（调用方据此决定是否重建 viewer）。
     * 只改内存状态，不写 manga.readingMode。
     */
    private fun markAutoWebtoonForChapter(chapterUrl: String): Boolean {
        val previousMode = getMangaReadingMode()
        autoWebtoonAspectDone = true
        autoWebtoonEffectiveChapter = chapterUrl
        // Komiho: 探测命中 ⇒ 按书记下「这本书是条漫」（会话内），后续章节直接按条漫起。
        autoWebtoonMangaId = manga?.id
        // 已经是条漫（全局默认 / 标签推断 / 本书记忆）时只是记账，模式不变 ⇒ 不需要重建
        return getMangaReadingMode() != previousMode
    }

    /**
     * Komiho: 与「自动条漫」两根内存标记**无关**的判定是否已经给出条漫 ——
     * 全局默认阅读模式就是条漫，或标签/来源推断（`defaultReaderType`）指向条漫。
     *
     * 这类书不需要再做比例探测，也不该被复核撤销（复核只服务于自动探测出来的结果）。
     */
    private fun isWebtoonByTagOrDefault(manga: Manga): Boolean {
        if (readerPreferences.defaultReadingMode.get() == ReadingMode.WEBTOON.flagValue) return true
        val type = manga.mangaType(sourceName = sourceManager.get(manga.source)?.name)
        return manga.defaultReaderType(type) == ReadingMode.WEBTOON.flagValue
    }

    /**
     * Komiho: 复核不过 —— 本章开头几页全部量完且都不是长条 ⇒ 撤销「本书是条漫」的会话记忆，
     * 退回标签/默认推断的模式（混排系列：条漫章后面跟着页漫章）。
     *
     * [rebuild] = true 时自己发重建事件（喂章节**之后**才得出结论的那条路）；
     * = false 时只改状态并返回「模式是否变化」，由 [preResolveAutoWebtoon] 在喂章节前重建。
     */
    private fun revertAutoWebtoonMangaMemoryIfNeeded(rebuild: Boolean = true): Boolean {
        val mangaId = manga?.id ?: return false
        if (autoWebtoonMangaId != mangaId) return false
        val previousMode = getMangaReadingMode()
        autoWebtoonMangaId = null
        autoWebtoonEffectiveChapter = null
        val changed = getMangaReadingMode() != previousMode
        if (!changed) return false
        logcat { "MihonSY auto-webtoon: book-level memory revoked, back to page mode" }
        android.util.Log.d(
            KOMIHA_AUTOWEBTOON_TAG,
            "revert: book-level memory revoked (early pages are not tall)",
        )
        if (rebuild) recreateViewerForAutoMode()
        return true
    }

    /**
     * MihonSY: 比例检测命中后调用——把当前章标记为「自动条漫」并按需重建 viewer。
     * 只改内存状态（[autoWebtoonEffectiveChapter]），不写 manga.readingMode：
     * 持久模式记忆仅由用户手动切换（[setMangaReadingMode]）更新，自动判断永不污染。
     *
     * Komiho: 记账交给 [markAutoWebtoonForChapter]；这里额外负责重建 viewer。
     * （[preResolveAutoWebtoon] 走另一条路：它在喂章节前就知道模式，由调用方直接重建，不发事件。）
     */
    private fun applyAutoWebtoonForCurrentChapter(chapterUrl: String) {
        val previousMode = getMangaReadingMode()
        if (!markAutoWebtoonForChapter(chapterUrl)) return
        logcat { "MihonSY auto-webtoon: chapter $chapterUrl switches to webtoon (in-memory, not saved)" }
        // Komiho 诊断：这条切换会让 Activity 重建 viewer ⇒ 所有可见页重新解码 + 增强。
        // 上面那句 logcat{} 走 XLog，不进 logcat，所以这里补一条 android.util.Log。
        android.util.Log.d(
            KOMIHA_AUTOWEBTOON_TAG,
            "auto-webtoon switch chapter=$chapterUrl mode=$previousMode->${getMangaReadingMode()} " +
                "(viewer will be recreated)",
        )
        recreateViewerForAutoMode()
    }

    /**
     * MihonSY: 不落库地按 [getMangaReadingMode] 重建 viewer（自动模式切换专用）。
     * 与 [setMangaReadingMode] 相同的位置保存逻辑，但不写数据库、不发 ReloadViewerChapters
     * （状态未变，直接发 RecreateViewer 让 ReaderActivity 重建并重喂章节）。
     */
    private fun recreateViewerForAutoMode() {
        val currChapters = state.value.viewerChapters ?: return
        val currChapter = currChapters.currChapter
        // MihonSY: 不重置 requestedPage——它随翻页/onPageSelected 实时维护（loadChapter 也会按
        // intent page 设好）。旧实现这里强制回退到 last_page_read，会把「从书签跳页/刚进入时」
        // 的位置冲掉，自动条漫判定一命中就跳回第一页或上次阅读页。
        // Komiho 诊断：viewer 重建 = 可见页全部重算（增强不留缓存），值得在日志里留痕。
        android.util.Log.d(
            KOMIHA_AUTOWEBTOON_TAG,
            "recreateViewerForAutoMode: sending Event.RecreateViewer " +
                "(requestedPage=${currChapter.requestedPage})",
        )
        // Channel 默认 RENDEZVOUS，trySend 在无接收者时会丢——用 send 保证送达
        viewModelScope.launchIO { eventChannel.send(Event.RecreateViewer) }
    }

    /**
     * Reads the intrinsic width/height of [page]'s image (without decoding pixels)
     * and returns height/width, or null when the image cannot be read.
     */
    private fun measurePageAspectRatio(page: ReaderPage): Float? {
        return try {
            val inputStream = page.stream?.invoke() ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            inputStream.use { BitmapFactory.decodeStream(it, null, options) }
            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) null else height.toFloat() / width
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "MihonSY auto-webtoon aspect check failed" }
            null
        }
    }

    private companion object {
        /** Height/width ratio above which a page is considered a webtoon strip. */
        const val AUTO_WEBTOON_MIN_ASPECT_RATIO = 2.5f

        /**
         * Number of leading pages inspected before giving up. Cover pages (normal ratio)
         * are skipped implicitly: a webtoon whose strips start after the cover is still
         * detected as long as any of the first few pages is a tall strip.
         */
        const val AUTO_WEBTOON_PAGES_TO_CHECK = 5

        /** Max delayed re-checks while waiting for early pages to finish downloading. */
        const val AUTO_WEBTOON_RETRIES = 4

        /**
         * Komiho: [preResolveAutoWebtoon] 的探测预算（ms）。超预算就不再继续量，
         * 剩下的交给 [maybeAutoWebtoonByAspectRatio] 兜底 —— 首屏不等慢源。
         */
        const val PRE_RESOLVE_BUDGET_MS = 150L

        /** Minimum interval between Komga page-progress syncs while reading (ms). */
        const val KOMGA_PAGE_SYNC_INTERVAL_MS = 5_000L
    }
    // MihonSY <--

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                // SY -->
                manga.ogTitle,
                // SY <--
                manga.source,
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe.get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            downloadManager.downloadChapters(
                manga,
                chaptersToDownload,
            )
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots.get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(
        readerChapter: ReaderChapter,
        page: Page/* SY --> */,
        hasExtraPage: Boolean, /* SY <-- */
    ) {
        val pageIndex = page.index

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            // SY -->
            // Remember what the database held, so the write below can be skipped when nothing
            // actually changed. Every UPDATE refreshes chapters.last_modified_at, and progress
            // merging uses that timestamp to decide which side wins, so a no-op write would make
            // this device look newer than it is and overwrite another device's real progress.
            val previousPageRead = readerChapter.chapter.last_page_read
            val previousRead = readerChapter.chapter.read
            // SY <--

            readerChapter.chapter.last_page_read = pageIndex

            // MihonSY: while reading, sync partial page progress to Komga (throttled).
            // Komga records "read to page N" per book, so mid-chapter progress is kept in
            // sync even before the chapter is completed.
            syncKomgaPageProgress(readerChapter, pageIndex)

            // SY -->
            if (
                readerChapter.pages?.lastIndex == pageIndex ||
                (hasExtraPage && readerChapter.pages?.lastIndex?.minus(1) == page.index)
            ) {
                // SY <--
                updateChapterProgressOnComplete(readerChapter)
                // SY -->
                // "Sync after reading" now fires when the reader is left (onCleared) instead of once
                // per finished chapter, and "sync on chapter open" is served by the lightweight
                // progress channel inside loadChapter.
                // SY <--
            }

            // SY -->
            // The history screen shows "read / total pages", and the total is only known while a
            // chapter is loaded, so it is recorded next to the progress into the chapter's memo.
            val memoWithPages = ChapterMemo.withPages(readerChapter.chapter.memo, readerChapter.pages?.size ?: 0)
            val pagesChanged = memoWithPages !== readerChapter.chapter.memo
            val progressChanged = previousPageRead != readerChapter.chapter.last_page_read ||
                previousRead != readerChapter.chapter.read
            if (progressChanged || pagesChanged) {
                updateChapter.await(
                    ChapterUpdate(
                        id = readerChapter.chapter.id!!,
                        read = readerChapter.chapter.read,
                        lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                        memo = memoWithPages.takeIf { pagesChanged },
                    ),
                )
                if (pagesChanged) {
                    // Keep the in-memory copy in step so the next page turn does not rewrite it
                    readerChapter.chapter.memo = memoWithPages
                }
                if (progressChanged) {
                    // Only a real change advances the reading clock. The chapter row's own timestamp
                    // cannot be used for this: bookmark edits and sync restores refresh it as well.
                    manga?.let {
                        ProgressClock.stampProgress(it.source, it.url, readerChapter.chapter.url)
                    }
                }
            }

            // Report to the lightweight progress channel. Throttled inside, so most page turns cost
            // nothing more than a comparison.
            readerChapter.chapter.id?.let { id ->
                progressSyncManager.recordLocalProgress(id)
            }
            // SY <--
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        // SY -->
        if (manga?.isEhBasedManga() == true) {
            viewModelScope.launchNonCancellable {
                // Re-marking an already read chapter is not a progress change, so it is filtered
                // out and kept from advancing the reading clock.
                val chaptersToMarkRead = unfilteredChapterList
                    .filter { !it.read && it.sourceOrder > readerChapter.chapter.source_order }
                updateChapter.awaitAll(
                    chaptersToMarkRead.map { ChapterUpdate(id = it.id, read = true) },
                )
                manga?.let { ProgressClock.stampProgress(it, chaptersToMarkRead) }
            }
        }
        // SY <--

        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .filter { chapter ->
                !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
            }
        updateChapter.awaitAll(
            duplicateUnreadChapters.map { ChapterUpdate(id = it.id, read = true) },
        )
        manga?.let { ProgressClock.stampProgress(it, duplicateUnreadChapters) }
        // SY -->
        duplicateUnreadChapters.forEach { chapter ->
            deleteChapterIfNeeded(ReaderChapter(chapter))
        }
        // SY <--
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
            chapterReadStartTime = null
        }
    }

    // SY -->
    /**
     * Pulls the newest progress for the chapter being read, bypassing the ordinary pull throttle.
     *
     * Called when the reader comes back to the foreground: this device may have been sitting on a
     * stale page while another one read further, and continuing from that stale page would overwrite
     * the newer progress on the next sync.
     */
    suspend fun refreshProgressFromRemote() {
        val readerChapter = getCurrentChapter() ?: return
        val currentManga = manga ?: return
        val chapter = readerChapter.chapter

        val applied = progressSyncManager
            .applyRemoteProgress(currentManga.id, chapter.url, force = true)
            ?: return

        chapter.last_page_read = applied.lastPageRead.toInt()
        chapter.read = applied.read
        readerChapter.requestedPage = applied.lastPageRead.toInt()
        // Needs `send` rather than `trySend`: the channel is rendezvous, so a trySend with no parked
        // receiver is silently dropped and the viewer would keep showing the stale page.
        eventChannel.send(Event.RecreateViewer)
        logcat(LogPriority.DEBUG) {
            "Reader resumed on a newer page from another device: ${applied.lastPageRead}"
        }
    }

    /**
     * Flushes the current chapter's progress onto the lightweight channel. Forced, because the
     * screen going off or the reader being closed is exactly when the last page matters.
     */
    suspend fun commitProgress() {
        getCurrentChapter()?.chapter?.id?.let { id ->
            progressSyncManager.recordLocalProgress(id, force = true)
        }
    }
    // SY <--

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = getSource() ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    // SY --> Komiho: 按页书签 —— 一本书（章节）可加多个，顶栏按钮在当前页加/取消。
    /** 在当前页切换书签：已标则取消，未标则新增。 */
    fun toggleBookmarkAtCurrentPage() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val chapterId = chapter.id ?: return
        val page = (state.value.currentPage - 1).coerceAtLeast(0)
        viewModelScope.launchNonCancellable {
            val marked = bookmarkRepository.isPageBookmarked(chapterId, page)
            if (marked) {
                bookmarkRepository.removeBookmarkAtPage(chapterId, page)
            } else {
                bookmarkRepository.addBookmark(chapterId, page)
            }
            mutableState.update { it.copy(currentPageBookmarked = !marked) }
        }
    }

    /** 在当前页新增书签（用于列表对话框的「加书签」按钮，已存在则忽略）。 */
    fun addBookmarkAtCurrentPage() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val chapterId = chapter.id ?: return
        val page = (state.value.currentPage - 1).coerceAtLeast(0)
        viewModelScope.launchNonCancellable {
            bookmarkRepository.addBookmark(chapterId, page)
            mutableState.update { it.copy(currentPageBookmarked = true) }
        }
    }

    /** 删除指定书签（列表对话框）。 */
    fun removeBookmark(id: Long) {
        viewModelScope.launchNonCancellable {
            bookmarkRepository.removeBookmark(id)
            getCurrentChapter()?.chapter?.id?.let { cid ->
                val marked = bookmarkRepository.isPageBookmarked(
                    cid,
                    (state.value.currentPage - 1).coerceAtLeast(0),
                )
                mutableState.update { it.copy(currentPageBookmarked = marked) }
            }
        }
    }

    /** 打开本书签列表对话框。 */
    fun openBookmarksDialog() {
        mutableState.update { it.copy(dialog = Dialog.Bookmarks) }
    }

    /** 取当前章节下的所有按页书签，供列表对话框展示。 */
    suspend fun getBookmarksForCurrentChapter(): List<BookmarkItem> {
        val chapterId = getCurrentChapter()?.chapter?.id ?: return emptyList()
        return bookmarkRepository.getBookmarksByChapter(chapterId)
    }
    // SY <--

    // SY -->
    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        val chapter = chapterList.find { it.chapter.id == chapterId }?.chapter ?: return
        // Only a real change is worth a clock stamp: the chapter row's own timestamp is refreshed
        // by any write, so stamping a no-op toggle would let this device win the next merge.
        val changed = chapter.bookmark != bookmarked
        chapter.bookmark = bookmarked
        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapterId,
                    bookmark = bookmarked,
                    bookmarkPage = if (bookmarked) chapter.last_page_read.toLong() else 0L,
                ),
            )
            if (changed) {
                manga?.let { ProgressClock.stampBookmark(it.source, it.url, chapter.url) }
            }
        }
    }
    // SY <--

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode.get()
        val manga = manga ?: return default
        val readingMode = ReadingMode.fromPreference(manga.readingMode.toInt())
        // SY -->
        return when {
            // Komiho:「这本书是条漫」的会话记忆 —— 本书任意一章探测命中后，其余章节直接按条漫解析。
            // 少了这一条，换章会回落默认模式，然后新章重新探到长条再切一次（每章一次 viewer 翻转 +
            // 一次模式提示）；有了它，后续章节一开始就是条漫，探测照跑但不再改变模式。
            resolveDefault && readingMode == ReadingMode.DEFAULT &&
                readerPreferences.useAutoWebtoon.get() &&
                autoWebtoonMangaId == manga.id -> {
                ReadingMode.WEBTOON.flagValue
            }
            // MihonSY: 比例检测命中的当前章——内存级 effective 模式优先于标签/全局推断，
            // 仅当该章仍是激活章时生效（换章后自然失效，由新章重新检测）。
            resolveDefault && readingMode == ReadingMode.DEFAULT &&
                readerPreferences.useAutoWebtoon.get() &&
                autoWebtoonEffectiveChapter != null &&
                autoWebtoonEffectiveChapter == state.value.viewerChapters?.currChapter?.chapter?.url -> {
                ReadingMode.WEBTOON.flagValue
            }
            resolveDefault && readingMode == ReadingMode.DEFAULT && readerPreferences.useAutoWebtoon.get() -> {
                manga.defaultReaderType(manga.mangaType(sourceName = sourceManager.get(manga.source)?.name))
                    ?: default
            }
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga.readingMode.toInt()
        }
        // SY <--
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        // Komiho: 手动选择永远优先，并清掉自动条漫的内存标记 —— 以后把模式改回「默认」时
        // 重新从零探测，而不是沿用这次残留的「本书是条漫」记忆。
        autoWebtoonMangaId = null
        autoWebtoonEffectiveChapter = null
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType.get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    // SY -->
    fun toggleCropBorders(): Boolean {
        val readingMode = getMangaReadingMode()
        val isPagerType = ReadingMode.isPagerType(readingMode)
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        return if (isPagerType) {
            readerPreferences.cropBorders.toggle()
        } else if (isWebtoon) {
            readerPreferences.cropBordersWebtoon.toggle()
        } else {
            readerPreferences.cropBordersContinuousVertical.toggle()
        }
    }
    // SY <--

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            DiskUtil.MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    // SY -->
    fun showEhUtils(visible: Boolean) {
        mutableState.update { it.copy(ehUtilsVisible = visible) }
    }

    fun setIndexChapterToShift(index: Long?) {
        mutableState.update { it.copy(indexChapterToShift = index) }
    }

    fun setIndexPageToShift(index: Int?) {
        mutableState.update { it.copy(indexPageToShift = index) }
    }

    fun openChapterListDialog() {
        mutableState.update { it.copy(dialog = Dialog.ChapterList) }
    }

    fun setDoublePages(doublePages: Boolean) {
        mutableState.update { it.copy(doublePages = doublePages) }
    }

    fun openAutoScrollHelpDialog() {
        mutableState.update { it.copy(dialog = Dialog.AutoScrollHelp) }
    }

    fun openBoostPageHelp() {
        mutableState.update { it.copy(dialog = Dialog.BoostPageHelp) }
    }

    fun openRetryAllHelp() {
        mutableState.update { it.copy(dialog = Dialog.RetryAllHelp) }
    }

    fun toggleAutoScroll(enabled: Boolean) {
        mutableState.update { it.copy(autoScroll = enabled) }
    }

    fun setAutoScrollFrequency(frequency: String) {
        mutableState.update { it.copy(ehAutoscrollFreq = frequency) }
    }
    // SY <--

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage/* SY --> */, extraPage: ReaderPage? = null/* SY <-- */) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page, extraPage)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    // SY --> Komiho: 加密本密码输入
    fun openArchivePasswordDialog() {
        mutableState.update { it.copy(dialog = Dialog.ArchivePassword()) }
    }

    fun submitArchivePassword(password: String) {
        CbzCrypto.setPassword(password)
        // SY --> Komiho: 渲染期页面错误弹的密码框没有经过 init 暂存，兜底当前章
        val chapter = archivePasswordChapter ?: getCurrentChapter() ?: return
        // SY <--
        mutableState.update { it.copy(dialog = null) }
        viewModelScope.launchIO {
            try {
                loadChapter(loader!!, chapter, archivePasswordPage)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is ArchivePasswordException) {
                    mutableState.update { it.copy(dialog = Dialog.ArchivePassword(wrongPassword = e.wrongPassword)) }
                    return@launchIO
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // SY <--

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    fun saveImage(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga.get()) {
            DiskUtil.buildValidFilename(
                manga.title,
            )
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    // SY -->
    fun saveImages() {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return

        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    location = Location.Pictures.create(DiskUtil.buildValidFilename(manga.title)),
                    manga = manga,
                )
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    private fun saveImages(
        page1: ReaderPage,
        page2: ReaderPage,
        isLTR: Boolean,
        @ColorInt bg: Int,
        location: Location,
        manga: Manga,
    ): Uri {
        val stream1 = page1.stream!!
        ImageUtil.findImageType(stream1) ?: throw Exception("Not an image")
        val stream2 = page2.stream!!
        ImageUtil.findImageType(stream2) ?: throw Exception("Not an image")
        val imageBitmap = ImageDecoder.newInstance(stream1())?.decode()!!
        val imageBitmap2 = ImageDecoder.newInstance(stream2())?.decode()!!

        val chapter = page1.chapter.chapter

        // Build destination file.
        val filenameSuffix = " - ${page1.number}-${page2.number}.jpg"
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}".takeBytes(MAX_FILE_NAME_BYTES - filenameSuffix.byteSize()),
        ) + filenameSuffix

        return imageSaver.save(
            image = Image.Page(
                inputStream = { ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, 0, bg).inputStream() },
                name = filename,
                location = location,
            ),
        )
    }
    // SY <--

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(copyToClipboard: Boolean, useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    // SY -->
    fun shareImages(copyToClipboard: Boolean) {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    location = Location.Cache,
                    manga = manga,
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, firstPage, secondPage))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }
    // SY <--

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (e: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /** Timestamp of the last throttled Komga page-progress sync (ms since epoch). */
    @Volatile
    private var lastKomgaPageSyncTimestamp = 0L

    /**
     * MihonSY: syncs the current page position of an unfinished chapter to Komga, so Komga
     * records "read to page N" even before the chapter is completed. Throttled to avoid
     * spamming the server on every page turn; the completion path handles the final update.
     */
    private fun syncKomgaPageProgress(readerChapter: ReaderChapter, pageIndex: Int) {
        if (!trackPreferences.autoUpdateTrack.get()) return
        val manga = manga ?: return
        val pages = readerChapter.pages ?: return
        if (pageIndex >= pages.lastIndex) return // completion path (updateTrackChapterRead) handles it
        val now = System.currentTimeMillis()
        if (now - lastKomgaPageSyncTimestamp < KOMGA_PAGE_SYNC_INTERVAL_MS) return
        lastKomgaPageSyncTimestamp = now

        viewModelScope.launchNonCancellable {
            trackChapter.updateKomgaPageProgress(
                manga.id,
                readerChapter.chapter.chapter_number.toDouble(),
                pageIndex + 1,
            )
        }
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack.get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val mergedManga = state.value.mergedManga
        // SY -->
        val manga = if (mergedManga.isNullOrEmpty()) {
            manga
        } else {
            mergedManga[chapter.chapter.manga_id]
        } ?: return
        // SY <--

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
            tempFileManager.deleteTempFiles()
        }
    }

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val currentPageBookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,

        // SY -->
        val currentPageText: String = "",
        val meta: RaisedSearchMetadata? = null,
        val mergedManga: Map<Long, Manga>? = null,
        val ehUtilsVisible: Boolean = false,
        val lastShiftDoubleState: Boolean? = null,
        val indexPageToShift: Int? = null,
        val indexChapterToShift: Long? = null,
        val doublePages: Boolean = false,
        val dateRelativeTime: Boolean = true,
        val autoScroll: Boolean = false,
        val isAutoScrollEnabled: Boolean = false,
        val ehAutoscrollFreq: String = "",
        // SY <--
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog

        // SY -->
        data object ChapterList : Dialog
        // SY --> Komiho: 阅读器内按页书签列表对话框
        data object Bookmarks : Dialog
        // SY <--

        data class PageActions(
            val page: ReaderPage/* SY --> */,
            val extraPage: ReaderPage? = null, /* SY <-- */
        ) : Dialog

        // SY --> Komiho: 加密归档密码输入对话框
        data class ArchivePassword(
            val wrongPassword: Boolean = false,
        ) : Dialog
        // SY <--

        // SY -->
        data object AutoScrollHelp : Dialog
        data object RetryAllHelp : Dialog
        data object BoostPageHelp : Dialog
        // SY <--
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        // MihonSY: 自动模式切换（内存生效，不落库）后按当前解析模式重建 viewer
        data object RecreateViewer : Event
        data object PageChanged : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(
            val uri: Uri,
            val page: ReaderPage/* SY --> */,
            val secondPage: ReaderPage? = null, /* SY <-- */
        ) : Event
        data class CopyImage(val uri: Uri) : Event
    }
}
