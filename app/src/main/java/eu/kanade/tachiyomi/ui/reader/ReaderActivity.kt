package eu.kanade.tachiyomi.ui.reader

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.SeekBar
import com.afollestad.materialdialogs.MaterialDialog
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.elvishew.xlog.XLog
import com.jakewharton.rxbinding.view.clicks
import com.jakewharton.rxbinding.widget.checkedChanges
import com.jakewharton.rxbinding.widget.textChanges
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.ui.base.activity.BaseRxActivity
import eu.kanade.tachiyomi.ui.reader.ReaderPresenter.SetAsCoverResult.*
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.BaseViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.*
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.lang.plusAssign
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.GLUtil
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.gone
import eu.kanade.tachiyomi.util.view.visible
import eu.kanade.tachiyomi.widget.SimpleAnimationListener
import eu.kanade.tachiyomi.widget.SimpleSeekBarListener
import kotlinx.android.synthetic.main.reader_activity.*
import me.zhanghai.android.systemuihelper.SystemUiHelper
import nucleus.factory.RequiresPresenter
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.math.abs

/**
 * Activity containing the reader of Tachiyomi. This activity is mostly a container of the
 * viewers, to which calls from the presenter or UI events are delegated.
 */
@RequiresPresenter(ReaderPresenter::class)
class ReaderActivity : BaseRxActivity<ReaderPresenter>() {

    /**
     * Preferences helper.
     */
    private val preferences by injectLazy<PreferencesHelper>()

    /**
     * The maximum bitmap size supported by the device.
     */
    val maxBitmapSize by lazy { GLUtil.maxTextureSize }

    /**
     * Viewer used to display the pages (pager, webtoon, ...).
     */
    var viewer: BaseViewer? = null
        private set

    /**
     * Whether the menu is currently visible.
     */
    var menuVisible = false
        private set

    // --> EH
    private var ehUtilsVisible = false

    private val exhSubscriptions = CompositeSubscription()

    private var autoscrollSubscription: Subscription? = null
    private val sourceManager: SourceManager by injectLazy()
    private val prefs: PreferencesHelper by injectLazy()

    private val logger = XLog.tag("ReaderActivity")
    // <-- EH

    /**
     * System UI helper to hide status & navigation bar on all different API levels.
     */
    private var systemUi: SystemUiHelper? = null

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    /**
     * Progress dialog used when switching chapters from the menu buttons.
     */
    @Suppress("DEPRECATION")
    private var progressDialog: ProgressDialog? = null

    companion object {
        @Suppress("unused")
        const val LEFT_TO_RIGHT = 1
        const val RIGHT_TO_LEFT = 2
        const val VERTICAL = 3
        const val WEBTOON = 4

        fun newIntent(context: Context, manga: Manga, chapter: Chapter): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", manga.id)
                putExtra("chapter", chapter.id)
                // chapters just added from library updates don't have an id yet
                putExtra("chapterUrl", chapter.url)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedState: Bundle?) {
        setTheme(when (preferences.readerTheme().getOrDefault()) {
            0 -> R.style.Theme_Reader_Light
            else -> R.style.Theme_Reader
        })
        super.onCreate(savedState)
        setContentView(R.layout.reader_activity)

        if (presenter.needsInit()) {
            val manga = intent.extras!!.getLong("manga", -1)
            val chapter = intent.extras!!.getLong("chapter", -1)
            val chapterUrl = intent.extras!!.getString("chapterUrl", "")
            if (manga == -1L || chapterUrl == "" && chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)
            if (chapter > -1) presenter.init(manga, chapter)
            else presenter.init(manga, chapterUrl)
        }

        if (savedState != null) {
            menuVisible = savedState.getBoolean(::menuVisible.name)
            // --> EH
            ehUtilsVisible = savedState.getBoolean(::ehUtilsVisible.name)
            // <-- EH
        }

        config = ReaderConfig()
        initializeMenu()
    }

    // --> EH
    private fun setEhUtilsVisibility(visible: Boolean) {
        if(visible) {
            eh_utils.visible()
            expand_eh_button.setImageResource(R.drawable.ic_keyboard_arrow_up_white_32dp)
        } else {
            eh_utils.gone()
            expand_eh_button.setImageResource(R.drawable.ic_keyboard_arrow_down_white_32dp)
        }
    }
    // <-- EH

    // --> EH
    private fun setupAutoscroll(interval: Float) {
        exhSubscriptions.remove(autoscrollSubscription)
        autoscrollSubscription = null

        if(interval == -1f) return

        val intervalMs = (interval * 1000).roundToLong()
        val sub = Observable.interval(intervalMs, intervalMs, TimeUnit.MILLISECONDS)
                .onBackpressureDrop()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    viewer.let { v ->
                        if(v is PagerViewer) v.moveToNext()
                        else if(v is WebtoonViewer) v.scrollDown()
                    }
                }

        autoscrollSubscription = sub
        exhSubscriptions += sub
    }
    // <-- EH

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewer?.destroy()
        viewer = null
        config?.destroy()
        config = null
        progressDialog?.dismiss()
        progressDialog = null
    }

    /**
     * Called when the activity is saving instance state. Current progress is persisted if this
     * activity isn't changing configurations.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(::menuVisible.name, menuVisible)
        // EXH -->
        outState.putBoolean(::ehUtilsVisible.name, ehUtilsVisible)
        // EXH <--
        if (!isChangingConfigurations) {
            presenter.onSaveInstanceStateNonConfigurationChange()
        }
        super.onSaveInstanceState(outState)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply again System UI (for immersive mode).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(menuVisible, animate = false)
        }
    }

    /**
     * Called when the options menu of the toolbar is being created. It adds our custom menu.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    /**
     * Called when an item of the options menu was clicked. Used to handle clicks on our menu
     * entries.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> ReaderSettingsSheet(this).show()
            R.id.action_custom_filter -> ReaderColorFilterSheet(this).show()
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun onBackPressed() {
        presenter.onBackPressed()
        super.onBackPressed()
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    /**
     * Initializes the reader menu. It sets up click listeners and the initial visibility.
     */
    private fun initializeMenu() {
        // Set toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        // Init listeners on bottom menu
        page_seekbar.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (viewer != null && fromUser) {
                    moveToPageIndex(value)
                }
            }
        })
        left_chapter.setOnClickListener {
            if (viewer != null) {
                if (viewer is R2LPagerViewer)
                    loadNextChapter()
                else
                    loadPreviousChapter()
            }
        }
        right_chapter.setOnClickListener {
            if (viewer != null) {
                if (viewer is R2LPagerViewer)
                    loadPreviousChapter()
                else
                    loadNextChapter()
            }
        }

        // --> EH
        exhSubscriptions += expand_eh_button.clicks().subscribe {
            ehUtilsVisible = !ehUtilsVisible
            setEhUtilsVisibility(ehUtilsVisible)
        }

        eh_autoscroll_freq.setText(preferences.eh_utilAutoscrollInterval().getOrDefault().let {
            if(it == -1f)
                ""
            else it.toString()
        })

        exhSubscriptions += eh_autoscroll.checkedChanges()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    setupAutoscroll(if(it)
                        preferences.eh_utilAutoscrollInterval().getOrDefault()
                    else -1f)
                }

        exhSubscriptions += eh_autoscroll_freq.textChanges()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    val parsed = it?.toString()?.toFloatOrNull()

                    if (parsed == null || parsed <= 0 || parsed > 9999) {
                        eh_autoscroll_freq.error = "Invalid frequency"
                        preferences.eh_utilAutoscrollInterval().set(-1f)
                        eh_autoscroll.isEnabled = false
                        setupAutoscroll(-1f)
                    } else {
                        eh_autoscroll_freq.error = null
                        preferences.eh_utilAutoscrollInterval().set(parsed)
                        eh_autoscroll.isEnabled = true
                        setupAutoscroll(if(eh_autoscroll.isChecked) parsed else -1f)
                    }
                }

        exhSubscriptions += eh_autoscroll_help.clicks().subscribe {
            MaterialDialog.Builder(this)
                    .title("Autoscroll help")
                    .content("Automatically scroll to the next page in the specified interval. Interval is specified in seconds.")
                    .positiveText("Ok")
                    .show()
        }

        exhSubscriptions += eh_retry_all.clicks().subscribe {
            var retried = 0

            presenter.viewerChaptersRelay.value
                    .currChapter
                    .pages
                    ?.forEachIndexed { index, page ->
                        var shouldQueuePage = false
                        if(page.status == Page.ERROR) {
                            shouldQueuePage = true
                        } else if(page.status == Page.LOAD_PAGE
                                || page.status == Page.DOWNLOAD_IMAGE) {
                            // Do nothing
                        }

                        if(shouldQueuePage) {
                            page.status = Page.QUEUE
                        } else {
                            return@forEachIndexed
                        }

                        //If we are using EHentai/ExHentai, get a new image URL
                        presenter.manga?.let { m ->
                            val src = sourceManager.get(m.source)
                            if(src is EHentai)
                                page.imageUrl = null
                        }

                        val loader = page.chapter.pageLoader
                        if(page.index == exh_currentPage()?.index && loader is HttpPageLoader) {
                            loader.boostPage(page)
                        } else {
                            loader?.retryPage(page)
                        }

                        retried++
                    }

            toast("Retrying $retried failed pages...")
        }

        exhSubscriptions += eh_retry_all_help.clicks().subscribe {
            MaterialDialog.Builder(this)
                    .title("Retry all help")
                    .content("Re-add all failed pages to the download queue.")
                    .positiveText("Ok")
                    .show()
        }

        exhSubscriptions += eh_boost_page.clicks().subscribe {
            viewer?.let { viewer ->
                val curPage = exh_currentPage() ?: run {
                    toast("This page cannot be boosted (invalid page)!")
                    return@let
                }

                if(curPage.status == Page.ERROR) {
                    toast("Page failed to load, press the retry button instead!")
                } else if(curPage.status == Page.LOAD_PAGE || curPage.status == Page.DOWNLOAD_IMAGE) {
                    toast("This page is already downloading!")
                } else if(curPage.status == Page.READY) {
                    toast("This page has already been downloaded!")
                } else {
                    val loader = (presenter.viewerChaptersRelay.value.currChapter.pageLoader as? HttpPageLoader)
                    if(loader != null) {
                        loader.boostPage(curPage)
                        toast("Boosted current page!")
                    } else {
                        toast("This page cannot be boosted (invalid page loader)!")
                    }
                }
            }
        }

        exhSubscriptions += eh_boost_page_help.clicks().subscribe {
            MaterialDialog.Builder(this)
                    .title("Boost page help")
                    .content("Normally the downloader can only download a specific amount of pages at the same time. This means you can be waiting for a page to download but the downloader will not start downloading the page until it has a free download slot. Pressing 'Boost page' will force the downloader to begin downloading the current page, regardless of whether or not there is an available slot.")
                    .positiveText("Ok")
                    .show()
        }
        // <-- EH

        // Set initial visibility
        setMenuVisibility(menuVisible)

        // --> EH
        setEhUtilsVisibility(ehUtilsVisible)
        // <-- EH
    }

    // EXH -->
    private fun exh_currentPage(): ReaderPage? {
        val currentPage = (((viewer as? PagerViewer)?.currentPage
                ?: (viewer as? WebtoonViewer)?.currentPage) as? ReaderPage)?.index
        return currentPage?.let { presenter.viewerChaptersRelay.value.currChapter.pages?.getOrNull(it) }
    }
    // EXH <--

    /**
     * Sets the visibility of the menu according to [visible] and with an optional parameter to
     * [animate] the views.
     */
    private fun setMenuVisibility(visible: Boolean, animate: Boolean = true) {
        menuVisible = visible
        if (visible) {
            systemUi?.show()
            reader_menu.visibility = View.VISIBLE

            if (animate) {
                val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_top)
                toolbarAnimation.setAnimationListener(object : SimpleAnimationListener() {
                    override fun onAnimationStart(animation: Animation) {
                        // Fix status bar being translucent the first time it's opened.
                        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    }
                })
                // EXH -->
                header.startAnimation(toolbarAnimation)
                // EXH <--

                val bottomAnimation = AnimationUtils.loadAnimation(this, R.anim.enter_from_bottom)
                reader_menu_bottom.startAnimation(bottomAnimation)
            }
        } else {
            systemUi?.hide()

            if (animate) {
                val toolbarAnimation = AnimationUtils.loadAnimation(this, R.anim.exit_to_top)
                toolbarAnimation.setAnimationListener(object : SimpleAnimationListener() {
                    override fun onAnimationEnd(animation: Animation) {
                        reader_menu.visibility = View.GONE
                    }
                })
                // EXH -->
                header.startAnimation(toolbarAnimation)
                // EXH <--

                val bottomAnimation = AnimationUtils.loadAnimation(this, R.anim.exit_to_bottom)
                reader_menu_bottom.startAnimation(bottomAnimation)
            }
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer
     * and the toolbar title.
     */
    fun setManga(manga: Manga) {
        val prevViewer = viewer
        val newViewer = when (presenter.getMangaViewer()) {
            RIGHT_TO_LEFT -> R2LPagerViewer(this)
            VERTICAL -> VerticalPagerViewer(this)
            WEBTOON -> WebtoonViewer(this)
            else -> L2RPagerViewer(this)
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            viewer_container.removeAllViews()
        }
        viewer = newViewer
        viewer_container.addView(newViewer.getView())

        toolbar.title = manga.title

        page_seekbar.isRTL = newViewer is R2LPagerViewer

        please_wait.visible()
        please_wait.startAnimation(AnimationUtils.loadAnimation(this, R.anim.fade_in_long))
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar.
     */
    fun setChapters(viewerChapters: ViewerChapters) {
        please_wait.gone()
        viewer?.setChapters(viewerChapters)
        toolbar.subtitle = viewerChapters.currChapter.chapter.name
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    fun setInitialChapterError(error: Throwable) {
        Timber.e(error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    @Suppress("DEPRECATION")
    fun setProgressDialog(show: Boolean) {
        progressDialog?.dismiss()
        progressDialog = if (show) {
            ProgressDialog.show(this, null, getString(R.string.loading), true)
        } else {
            null
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    fun moveToPageIndex(index: Int) {
        val viewer = viewer ?: return
        val currentChapter = presenter.getCurrentChapter() ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        presenter.loadNextChapter()
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        presenter.loadPreviousChapter()
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    @SuppressLint("SetTextI18n")
    fun onPageSelected(page: ReaderPage) {
        presenter.onPageSelected(page)
        val pages = page.chapter.pages ?: return

        // Set bottom page number
        page_number.text = "${page.number}/${pages.size}"

        // Set seekbar page number
        if (viewer !is R2LPagerViewer) {
            left_page_text.text = "${page.number}"
            right_page_text.text = "${pages.size}"
        } else {
            right_page_text.text = "${page.number}"
            left_page_text.text = "${pages.size}"
        }

        // Set seekbar progress
        page_seekbar.max = pages.lastIndex
        page_seekbar.progress = page.index
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage) {
        // EXH -->
        try {
            // EXH <--
            ReaderPageSheet(this, page).show()
            // EXH -->
        } catch(e: WindowManager.BadTokenException) {
            logger.e("Caught and ignoring reader page sheet launch exception!", e)
        }
        // EXH <--
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        presenter.preloadChapter(chapter)
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the page sheet. It delegates the call to the presenter to do some IO, which
     * will call [onShareImageResult] with the path the image was saved on when it's ready.
     */
    fun shareImage(page: ReaderPage) {
        presenter.shareImage(page)
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    fun onShareImageResult(file: File) {
        val stream = file.getUriCompat(this)
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, stream)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            type = "image/*"
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    /**
     * Called from the page sheet. It delegates saving the image of the given [page] on external
     * storage to the presenter.
     */
    fun saveImage(page: ReaderPage) {
        presenter.saveImage(page)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    fun onSaveImageResult(result: ReaderPresenter.SaveImageResult) {
        when (result) {
            is ReaderPresenter.SaveImageResult.Success -> {
                toast(R.string.picture_saved)
            }
            is ReaderPresenter.SaveImageResult.Error -> {
                Timber.e(result.error)
            }
        }
    }

    /**
     * Called from the page sheet. It delegates setting the image of the given [page] as the
     * cover to the presenter.
     */
    fun setAsCover(page: ReaderPage) {
        presenter.setAsCover(page)
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    fun onSetAsCoverResult(result: ReaderPresenter.SetAsCoverResult) {
        toast(when (result) {
            Success -> R.string.cover_updated
            AddToLibraryFirst -> R.string.notification_first_add_to_library
            Error -> R.string.notification_cover_update_failed
        })
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        /**
         * List of subscriptions to keep while the reader is alive.
         */
        private val subscriptions = CompositeSubscription()

        /**
         * Custom brightness subscription.
         */
        private var customBrightnessSubscription: Subscription? = null

        /**
         * Custom color filter subscription.
         */
        private var customFilterColorSubscription: Subscription? = null

        /**
         * Initializes the reader subscriptions.
         */
        init {
            val sharedRotation = preferences.rotation().asObservable().share()
            val initialRotation = sharedRotation.take(1)
            val rotationUpdates = sharedRotation.skip(1)
                .delay(250, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())

            subscriptions += Observable.merge(initialRotation, rotationUpdates)
                .subscribe { setOrientation(it) }

            subscriptions += preferences.readerTheme().asObservable()
                .skip(1) // We only care about updates
                .subscribe { recreate() }

            subscriptions += preferences.showPageNumber().asObservable()
                .subscribe { setPageNumberVisibility(it) }

            subscriptions += preferences.trueColor().asObservable()
                .subscribe { setTrueColor(it) }

            subscriptions += preferences.fullscreen().asObservable()
                .subscribe { setFullscreen(it) }

            subscriptions += preferences.keepScreenOn().asObservable()
                .subscribe { setKeepScreenOn(it) }

            subscriptions += preferences.customBrightness().asObservable()
                .subscribe { setCustomBrightness(it) }

            subscriptions += preferences.colorFilter().asObservable()
                .subscribe { setColorFilter(it) }

            subscriptions += preferences.colorFilterMode().asObservable()
                .subscribe { setColorFilter(preferences.colorFilter().getOrDefault()) }
        }

        /**
         * Called when the reader is being destroyed. It cleans up all the subscriptions.
         */
        fun destroy() {
            subscriptions.unsubscribe()
            customBrightnessSubscription = null
            customFilterColorSubscription = null
        }

        /**
         * Forces the user preferred [orientation] on the activity.
         */
        private fun setOrientation(orientation: Int) {
            val newOrientation = when (orientation) {
                // Lock in current orientation
                2 -> {
                    val currentOrientation = resources.configuration.orientation
                    if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
                // Lock in portrait
                3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                // Lock in landscape
                4 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                // Rotation free
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

            if (newOrientation != requestedOrientation) {
                requestedOrientation = newOrientation
            }
        }

        /**
         * Sets the visibility of the bottom page indicator according to [visible].
         */
        private fun setPageNumberVisibility(visible: Boolean) {
            page_number.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }

        /**
         * Sets the 32-bit color mode according to [enabled].
         */
        private fun setTrueColor(enabled: Boolean) {
            if (enabled)
                SubsamplingScaleImageView.setPreferredBitmapConfig(Bitmap.Config.ARGB_8888)
            else
                SubsamplingScaleImageView.setPreferredBitmapConfig(Bitmap.Config.RGB_565)
        }

        /**
         * Sets the fullscreen reading mode (immersive) according to [enabled].
         */
        private fun setFullscreen(enabled: Boolean) {
            systemUi = if (enabled) {
                val level = SystemUiHelper.LEVEL_IMMERSIVE
                val flags = SystemUiHelper.FLAG_IMMERSIVE_STICKY or
                        SystemUiHelper.FLAG_LAYOUT_IN_SCREEN_OLDER_DEVICES

                SystemUiHelper(this@ReaderActivity, level, flags)
            } else {
                null
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                customBrightnessSubscription = preferences.customBrightnessValue().asObservable()
                    .sample(100, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                    .subscribe { setCustomBrightnessValue(it) }

                subscriptions.add(customBrightnessSubscription)
            } else {
                customBrightnessSubscription?.let { subscriptions.remove(it) }
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the color filter overlay according to [enabled].
         */
        private fun setColorFilter(enabled: Boolean) {
            if (enabled) {
                customFilterColorSubscription = preferences.colorFilterValue().asObservable()
                    .sample(100, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                    .subscribe { setColorFilterValue(it) }

                subscriptions.add(customFilterColorSubscription)
            } else {
                customFilterColorSubscription?.let { subscriptions.remove(it) }
                color_overlay.visibility = View.GONE
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }

            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            // Set black overlay visibility.
            if (value < 0) {
                brightness_overlay.visibility = View.VISIBLE
                val alpha = (abs(value) * 2.56).toInt()
                brightness_overlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
            } else {
                brightness_overlay.visibility = View.GONE
            }
        }

        /**
         * Sets the color filter [value].
         */
        private fun setColorFilterValue(value: Int) {
            color_overlay.visibility = View.VISIBLE
            color_overlay.setFilterColor(value, preferences.colorFilterMode().getOrDefault())
        }

    }

}
