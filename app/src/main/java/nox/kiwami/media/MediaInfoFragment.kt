package nox.kiwami.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.speech.tts.TextToSpeech
import android.view.View
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.AbstractMarkwonPlugin
import nox.kiwami.others.ThemeSongs
import nox.kiwami.others.ThemeSong
import nox.kiwami.databinding.ItemThemeSongBinding
import androidx.core.graphics.ColorUtils
import android.widget.LinearLayout
import android.content.res.ColorStateList
import android.text.StaticLayout
import android.text.Layout
import com.google.android.material.color.MaterialColors
import android.view.animation.OvershootInterpolator
import android.speech.tts.UtteranceProgressListener
import android.animation.ValueAnimator
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import nox.kiwami.R
import nox.kiwami.snackString
import nox.kiwami.connections.anilist.Anilist
import nox.kiwami.connections.anilist.GenresViewModel
import nox.kiwami.copyToClipboard
import nox.kiwami.currActivity
import nox.kiwami.databinding.ActivityGenreBinding
import nox.kiwami.databinding.FragmentMediaInfoBinding
import nox.kiwami.databinding.ItemChipBinding
import nox.kiwami.databinding.ItemQuelsBinding
import nox.kiwami.databinding.ItemTitleChipgroupBinding
import nox.kiwami.databinding.ItemTitleRecyclerBinding
import nox.kiwami.databinding.ItemTitleTextBinding
import nox.kiwami.databinding.ItemTitleTrailerBinding
import nox.kiwami.loadImage
import nox.kiwami.navBarHeight
import nox.kiwami.setBaseline
import nox.kiwami.profile.User
import nox.kiwami.px
import nox.kiwami.setSafeOnClickListener
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName
import nox.kiwami.toast
import nox.kiwami.media.mangaupdates.MangaAnimeUtil
import nox.kiwami.util.Logger
import com.xwray.groupie.GroupieAdapter
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.net.URLEncoder
import java.util.Locale


class MediaInfoFragment : Fragment() {
    private var _binding: FragmentMediaInfoBinding? = null
    private val binding get() = _binding!!
    private var timer: CountDownTimer? = null
    private var tts: TextToSpeech? = null
    private var ttsPulse: ObjectAnimator? = null
    private var themePlayer: ThemeSongPlayer? = null
    private val themeRows = HashMap<ThemeSong, ThemeRowHolder>()

    override fun onPause() {
        themePlayer?.stop()
        super.onPause()
    }

    // heading sits right on top of its text (Opening / Ending)
    private fun tightTextSection(title: TextView, text: TextView) {
        val d = resources.displayMetrics.density
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = 0
            title.layoutParams = it
        }
        (text.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (3 * d).toInt()
            text.layoutParams = it
        }
    }

    // same look as the Synopsis / Details / Names headings
    private fun styleSectionHeading(tv: TextView) {
        val d = resources.displayMetrics.density
        tv.textSize = 20f
        tv.setTextColor(MaterialColors.getColor(tv, androidx.appcompat.R.attr.colorPrimary))
        tv.includeFontPadding = false
        tv.translationX = -2f * d
        tv.translationY = 3f * d
        (tv.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (18 * d).toInt()
            it.bottomMargin = (10 * d).toInt()
            tv.layoutParams = it
        }
    }
    private var descLayoutListener: View.OnLayoutChangeListener? = null

    private fun speakSynopsis(engine: TextToSpeech, text: String) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _binding?.mediaInfoTts?.post { setTtsActive(true) }
            }

            override fun onDone(utteranceId: String?) {
                _binding?.mediaInfoTts?.post { setTtsActive(false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _binding?.mediaInfoTts?.post { setTtsActive(false) }
            }
        })
        setTtsActive(true)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "synopsis_tts")
    }

    // theme-coloured, softly pulsing icon while the synopsis is being read
    private fun setTtsActive(active: Boolean) {
        val button = _binding?.mediaInfoTts ?: return
        ttsPulse?.cancel()
        ttsPulse = null
        if (active) {
            button.setColorFilter(
                MaterialColors.getColor(button, androidx.appcompat.R.attr.colorPrimary)
            )
            ttsPulse = ObjectAnimator.ofFloat(button, "alpha", 1f, 0.4f).apply {
                duration = 650
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else {
            button.clearColorFilter()
            button.alpha = 0.7f
        }
    }
    private var loaded = false
    private var type = "ANIME"
    private val genreModel: GenresViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        themePlayer?.stop()
        themePlayer = null
        themeRows.clear()
        timer?.cancel()
        timer = null
        ttsPulse?.cancel()
        ttsPulse = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
        _binding = null
    }

    // Method to display anime adaptation
    private fun displayAnimeAdaptation(adaptation: MangaAnimeUtil.AnimeAdaptation) {
        if (adaptation.hasAdaptation) {
            val adaptationText = buildString {
                append("Start: ${adaptation.animeStart ?: "Unknown"}\n")
                append("End: ${adaptation.animeEnd ?: "Ongoing"}")
            }

            binding.mediaAnimeAdaptation.text = adaptationText

            binding.mediaAnimeAdaptationText.fadeIn()
            binding.mediaAnimeAdaptation.fadeIn()
        } else {
            binding.mediaAnimeAdaptationText.fadeOut()
            binding.mediaAnimeAdaptation.fadeOut()
        }
    }
    private fun displayNextChapterPrediction(prediction: MangaAnimeUtil.NextRelease) {
        if (prediction.error == null && prediction.nextReleaseDate != null) {
            val dateFormat = java.text.SimpleDateFormat("d MMMM", java.util.Locale.US)

            val predictionText = buildString {
                append("Current: ${prediction.latestChapter ?: "Unknown"}\n")
                append("${prediction.nextChapter ?: "Next chapter"} releases on ${
                    dateFormat.format(prediction.nextReleaseDate)
                }")
            }

            binding.mediaNextChapterPrediction.text = predictionText

            binding.mediaNextChapterPredictionText.fadeIn()
            binding.mediaNextChapterPrediction.fadeIn()
        } else {
            binding.mediaNextChapterPredictionText.fadeOut()
            binding.mediaNextChapterPrediction.fadeOut()

            if (prediction.error != null) {
                Logger.log("Next chapter prediction error: ${prediction.error}")
            }
        }
    }
    fun View.fadeIn(duration: Long = 250) {
        if (isVisible) return
        alpha = 0f
        translationY = 20f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .start()
    }

    fun View.fadeOut(duration: Long = 200) {
        if (visibility != View.VISIBLE) return
        animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(duration)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                translationY = 0f
            }
            .start()
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val model: MediaDetailsViewModel by activityViewModels()
        binding.mediaInfoProgressBar.isGone = loaded
        binding.mediaInfoContainer.isVisible = loaded
        val activity = requireActivity() as MediaDetailsActivity
        // The host can be mid-bail after a process death; it has no views to anchor to.
        if (!activity.bindingReady) return
        val baselineAnchor = activity.binding.mediaBottomBarContainer ?: activity.binding.commentMessageContainer
        baselineAnchor.let {
            // If it's the unified container, it already has navBarHeight padding, so don't include it again.
            val includeSystemPaddings = it != activity.binding.mediaBottomBarContainer
            binding.mediaInfoScroll.setBaseline(it, includeSystemNavBar = includeSystemPaddings)
            binding.mediaInfoScroll.clipToPadding = false
        }

        model.scrolledToTop.observe(viewLifecycleOwner) {
            if (it) binding.mediaInfoScroll.scrollTo(0, 0)
        }

        model.getMedia().observe(viewLifecycleOwner) { media ->
            if (media != null) {
                loaded = true

                bindMediaStatsBar(binding, media)
                binding.mediaInfoProgressBar.visibility = View.GONE
                binding.mediaInfoContainer.visibility = View.VISIBLE
                binding.mediaInfoCountry.text = media.countryOfOrigin ?: "~"

                // Names section
                fun bindName(container: View, valueView: TextView, value: String?) {
                    if (value.isNullOrBlank()) {
                        container.visibility = View.GONE
                    } else {
                        container.visibility = View.VISIBLE
                        valueView.text = value
                        valueView.setOnLongClickListener { copyToClipboard(value); true }
                    }
                }
                bindName(binding.mediaInfoNameContainer, binding.mediaInfoName, media.name)
                bindName(
                    binding.mediaInfoNameRomajiContainer,
                    binding.mediaInfoNameRomaji,
                    media.nameRomaji
                )
                bindName(
                    binding.mediaInfoNamePreferredContainer,
                    binding.mediaInfoNamePreferred,
                    media.userPreferredName.takeIf { it != media.name && it != media.nameRomaji }
                )
                binding.mediaInfoFormat.text = media.format
                binding.mediaInfoSource.text = media.source
                binding.mediaInfoStart.text = media.startDate?.toString() ?: "~"
                if (media.anime != null) {
                    binding.mediaInfoSeasonContainer.visibility = View.VISIBLE
                    val seasonInfo =
                        "${(media.anime.season ?: "??")} ${(media.anime.seasonYear ?: "??")}"
                    binding.mediaInfoSeason.text = seasonInfo

                    if (media.anime.mainStudio != null) {
                        binding.mediaInfoStudioContainer.visibility = View.VISIBLE
                        binding.mediaInfoStudio.text = media.anime.mainStudio!!.name
                        binding.mediaInfoStudioContainer.setOnClickListener {
                            ContextCompat.startActivity(
                                requireActivity(),
                                Intent(requireContext(), StudioActivity::class.java).putExtra(
                                    "studio",
                                    media.anime.mainStudio!! as Serializable
                                ),
                                null
                            )
                        }
                    }
                    // Show producers as a chip group (same style as External Links)
                    if (!media.anime.producers.isNullOrEmpty()) {
                        val validProducers = media.anime.producers!!.filter { it.id != "null" }
                        if (validProducers.isNotEmpty()) {
                            val bind = ItemTitleChipgroupBinding.inflate(
                                LayoutInflater.from(context),
                                binding.mediaInfoContainer,
                                false
                            )
                            bind.itemTitle.text = getString(R.string.producers)
                            bind.root.tag = "dynamic_view"
                            binding.mediaInfoContainer.addView(
                                bind.root,
                                binding.mediaInfoContainer.indexOfChild(binding.mediaInfoNamesSection) + 1
                            )

                            validProducers.forEach { producer ->
                                val chip = ItemChipBinding.inflate(
                                    LayoutInflater.from(context),
                                    bind.itemChipGroup,
                                    false
                                ).root
                                chip.text = producer.name ?: ""
                                chip.setSafeOnClickListener {
                                    ContextCompat.startActivity(
                                        requireActivity(),
                                        Intent(activity, StudioActivity::class.java).putExtra(
                                            "studio",
                                            producer as Serializable
                                        ),
                                        null
                                    )
                                }
                                bind.itemChipGroup.addView(chip)
                            }
                        }
                    }
                    if (media.anime.author != null) {
                        binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
                        binding.mediaInfoAuthor.text = media.anime.author!!.name
                        binding.mediaInfoAuthorContainer.setOnClickListener {
                            ContextCompat.startActivity(
                                requireActivity(),
                                Intent(requireContext(), AuthorActivity::class.java).putExtra(
                                    "author",
                                    media.anime.author!! as Serializable
                                ),
                                null
                            )
                        }
                    }
                    binding.mediaInfoTotalTitle.setText(R.string.details_episodes)
                    val infoTotal = if (media.anime.nextAiringEpisode != null)
                        "${media.anime.nextAiringEpisode} | ${media.anime.totalEpisodes ?: "~"}"
                    else
                        (media.anime.totalEpisodes ?: "~").toString()
                    binding.mediaInfoTotal.text = infoTotal

                } else if (media.manga != null) {
                    type = "MANGA"
                    binding.mediaInfoTotalTitle.setText(R.string.details_chapters)
                    binding.mediaInfoTotal.text = (media.manga.totalChapters ?: "~").toString()
                    if (media.manga.author != null) {
                        binding.mediaInfoAuthorContainer.visibility = View.VISIBLE
                        binding.mediaInfoAuthor.text = media.manga.author!!.name
                        binding.mediaInfoAuthorContainer.setOnClickListener {
                            ContextCompat.startActivity(
                                requireActivity(),
                                Intent(activity, AuthorActivity::class.java).putExtra(
                                    "author",
                                    media.manga.author!! as Serializable
                                ),
                                null
                            )
                        }
                    }
                }

                val desc = HtmlCompat.fromHtml(
                    (media.description ?: "null").replace("\\n", "<br>").replace("\\\"", "\""),
                    HtmlCompat.FROM_HTML_MODE_LEGACY
                )
                val infoDesc =
                    if (desc.toString() != "null") desc else getString(R.string.no_description_available)
                binding.mediaInfoDescription.text = infoDesc

                // "Read more / Read less" link, only when the synopsis is actually cut off.
                // The last visible line fades out from the bottom instead of ending in "...".
                val collapsedLines = 5
                val descView = binding.mediaInfoDescription
                var expanded = false
                var lastWidth = -1
                var truncated = false
                descView.fadeAnimator?.cancel()
                descView.heightAnimator?.cancel()
                descView.fadeAmount = 0f
                binding.mediaInfoReadMore.text = getString(R.string.read_more)
                descView.maxLines = collapsedLines
                binding.mediaInfoReadMore.visibility = View.GONE

                fun animateFade(to: Float, duration: Long) {
                    descView.fadeAnimator?.cancel()
                    descView.fadeAnimator = ValueAnimator.ofFloat(descView.fadeAmount, to).apply {
                        this.duration = duration
                        addUpdateListener { descView.fadeAmount = it.animatedValue as Float }
                        start()
                    }
                }

                // Measures the full text at the current width. Never runs inside a layout pass:
                // changing visibility while the parent is being laid out can get the request dropped,
                // which is why the button used to stay hidden until the synopsis was toggled.
                fun updateReadMore() {
                    val b = _binding ?: return
                    val tv = b.mediaInfoDescription
                    val w = tv.width - tv.paddingLeft - tv.paddingRight
                    if (w <= 0) return
                    if (w != lastWidth) {
                        lastWidth = w
                        val full = if (Build.VERSION.SDK_INT >= 23) {
                            StaticLayout.Builder.obtain(infoDesc, 0, infoDesc.length, tv.paint, w)
                                .setLineSpacing(tv.lineSpacingExtra, tv.lineSpacingMultiplier)
                                .setIncludePad(tv.includeFontPadding)
                                .setBreakStrategy(tv.breakStrategy)
                                .setHyphenationFrequency(tv.hyphenationFrequency)
                                .build()
                        } else {
                            @Suppress("DEPRECATION")
                            StaticLayout(
                                infoDesc, tv.paint, w, Layout.Alignment.ALIGN_NORMAL,
                                tv.lineSpacingMultiplier, tv.lineSpacingExtra, tv.includeFontPadding
                            )
                        }
                        truncated = full.lineCount > collapsedLines
                    }
                    val target = if (truncated) View.VISIBLE else View.GONE
                    if (b.mediaInfoReadMore.visibility != target) b.mediaInfoReadMore.visibility = target
                    if (!expanded && descView.fadeAnimator?.isRunning != true) {
                        tv.fadeAmount = if (truncated) 1f else 0f
                    }
                }
                descLayoutListener?.let { descView.removeOnLayoutChangeListener(it) }
                descLayoutListener = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
                    // only the width matters; re-check after the layout pass has finished
                    if (right - left != oldRight - oldLeft) v.post { updateReadMore() }
                }
                descView.addOnLayoutChangeListener(descLayoutListener)
                descView.post { updateReadMore() }
                val toggleSynopsis = View.OnClickListener {
                    if (!truncated) return@OnClickListener
                    expanded = !expanded
                    SynopsisAnimator.toggle(
                        descView,
                        expanded,
                        collapsedLines,
                        binding.mediaInfoReadMore,
                        getString(if (expanded) R.string.read_less else R.string.read_more)
                    )
                }
                descView.setOnClickListener(toggleSynopsis)
                binding.mediaInfoReadMore.setOnClickListener(toggleSynopsis)

                // read the synopsis out loud (button squishes on tap and pulses while speaking)
                binding.mediaInfoTts.setOnClickListener { button ->
                    button.animate().cancel()
                    button.animate().scaleX(0.78f).scaleY(0.78f).setDuration(90).withEndAction {
                        button.animate().scaleX(1f).scaleY(1f)
                            .setInterpolator(OvershootInterpolator(2.2f)).setDuration(260).start()
                    }.start()

                    val text = infoDesc.toString()
                    val engine = tts
                    if (engine != null && engine.isSpeaking) {
                        engine.stop()
                        setTtsActive(false)
                    } else if (engine != null) {
                        speakSynopsis(engine, text)
                    } else {
                        tts = TextToSpeech(requireContext().applicationContext) { status ->
                            val created = tts
                            if (status == TextToSpeech.SUCCESS && created != null) {
                                created.language = Locale.getDefault()
                                speakSynopsis(created, text)
                            } else {
                                snackString("TTS is not available")
                            }
                        }
                    }
                }
                val parent = _binding?.mediaInfoContainer!!
                for (i in parent.childCount - 1 downTo 0) {
                    val child = parent.getChildAt(i)
                    if (child.tag == "dynamic_view") {
                        parent.removeViewAt(i)
                    }
                }

                val screenWidth = resources.displayMetrics.run { widthPixels / density }

                if (media.manga != null) {
                    model.loadMangaExtras(media)

                    model.adaptation.observe(viewLifecycleOwner) {
                        it?.let { displayAnimeAdaptation(it) }
                    }

                    model.nextRelease.observe(viewLifecycleOwner) {
                        it?.let { displayNextChapterPrediction(it) }
                    }
                }
                if (media.synonyms.isNotEmpty()) {
                    val bind = ItemTitleChipgroupBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    styleSectionHeading(bind.itemTitle)
                    bind.root.tag = "dynamic_view"
                    for (position in media.synonyms.indices) {
                        val chip = ItemChipBinding.inflate(
                            LayoutInflater.from(context),
                            bind.itemChipGroup,
                            false
                        ).root
                        chip.text = media.synonyms[position]
                        chip.setOnLongClickListener { copyToClipboard(media.synonyms[position]);true }
                        bind.itemChipGroup.addView(chip)
                    }
                    parent.addView(bind.root)
                }
                // "People watching" row: a slot at this position that is filled right away from the
                // loaded details and again with a fresh followers lookup (so caching can't hide it).
                val usersSlot = FrameLayout(requireContext()).apply {
                    tag = "dynamic_view"
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                parent.addView(usersSlot)
                fun showUsers(found: List<User>) {
                    if (found.isEmpty() || _binding == null) return
                    val users = ArrayList(found)
                    val currentUserId = Anilist.userid
                    if (Anilist.token != null && currentUserId != null && media.userStatus != null) {
                        users.add(
                            0,
                            User(
                                id = currentUserId,
                                name = getString(R.string.you),
                                pfp = Anilist.avatar,
                                banner = "",
                                status = media.userStatus,
                                score = media.userScore.toFloat(),
                                progress = media.userProgress,
                                totalEpisodes = media.anime?.totalEpisodes
                                    ?: media.manga?.totalChapters,
                                nextAiringEpisode = media.anime?.nextAiringEpisode
                            )
                        )
                    }
                    usersSlot.removeAllViews()
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        usersSlot,
                        false
                    ).apply {
                        itemTitle.visibility = View.GONE
                        itemRecycler.adapter =
                            MediaSocialAdapter(users, type, requireActivity())
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        usersSlot.addView(root)
                    }
                }
                media.users?.let { showUsers(it) }
                if (Anilist.token != null && media.id != 0) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val fresh = runCatching { Anilist.query.getMediaFollowers(media) }
                            .getOrDefault(arrayListOf())
                        withContext(Dispatchers.Main) { showUsers(fresh) }
                    }
                }

                if (media.trailer != null) {
                    @Suppress("DEPRECATION")
                    class MyChrome : WebChromeClient() {
                        private var mCustomView: View? = null
                        private var mCustomViewCallback: CustomViewCallback? = null
                        private var mOriginalSystemUiVisibility = 0

                        override fun onHideCustomView() {
                            (requireActivity().window.decorView as FrameLayout).removeView(
                                mCustomView
                            )
                            mCustomView = null
                            requireActivity().window.decorView.systemUiVisibility =
                                mOriginalSystemUiVisibility
                            mCustomViewCallback!!.onCustomViewHidden()
                            mCustomViewCallback = null
                        }

                        override fun onShowCustomView(
                            paramView: View,
                            paramCustomViewCallback: CustomViewCallback
                        ) {
                            if (mCustomView != null) {
                                onHideCustomView()
                                return
                            }
                            mCustomView = paramView
                            mOriginalSystemUiVisibility =
                                requireActivity().window.decorView.systemUiVisibility
                            mCustomViewCallback = paramCustomViewCallback
                            (requireActivity().window.decorView as FrameLayout).addView(
                                mCustomView,
                                FrameLayout.LayoutParams(-1, -1)
                            )
                            requireActivity().window.decorView.systemUiVisibility =
                                3846 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        }
                    }

                    val bind = ItemTitleTrailerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    styleSectionHeading(bind.mediaInfoTrailerText)
                    bind.mediaInfoTrailer.apply {
                        visibility = View.VISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        settings.userAgentString = null
                        isSoundEffectsEnabled = true
                        webChromeClient = MyChrome()

                        val trailerId = media.trailer!!

                        addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun loadVideo() {
                                val trailerHtml = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                        <style>
                                            * {
                                                margin: 0;
                                                padding: 0;
                                                box-sizing: border-box;
                                                -webkit-tap-highlight-color: transparent;
                                            }
                                            html, body {
                                                width: 100%;
                                                height: 100%;
                                                background: #000;
                                                overflow: hidden;
                                            }
                                            iframe {
                                                width: 100%;
                                                height: 100%;
                                                border: none;
                                                display: block;
                                            }
                                        </style>
                                    </head>
                                    <body>
                                        <iframe
                                            src="https://www.youtube-nocookie.com/embed/$trailerId?autoplay=1&rel=0&modestbranding=1&controls=1&fs=0"
                                            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
                                            frameborder="0">
                                        </iframe>
                                    </body>
                                    </html>
                                """.trimIndent()
                                context.let {
                                     (it as? android.app.Activity)?.runOnUiThread {
                                         loadDataWithBaseURL("https://www.youtube-nocookie.com", trailerHtml, "text/html", "utf-8", null)
                                     }
                                }
                            }
                        }, "Android")

                        val placeholderHtml = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    * {
                                        margin: 0;
                                        padding: 0;
                                        box-sizing: border-box;
                                        -webkit-tap-highlight-color: transparent;
                                        -webkit-touch-callout: none;
                                        -webkit-user-select: none;
                                        user-select: none;
                                    }
                                    body, html {
                                        width: 100%;
                                        height: 100%;
                                        background: #000;
                                        overflow: hidden;
                                    }
                                    .thumbnail-container {
                                        position: relative;
                                        width: 100%;
                                        height: 100%;
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        background: #000;
                                    }
                                    .thumbnail {
                                        width: 100%;
                                        height: 100%;
                                        object-fit: cover;
                                        transform-origin: center center;
                                    }
                                    .play-button {
                                        position: absolute;
                                        width: 68px;
                                        height: 48px;
                                        background: rgba(255, 0, 0, 0.8);
                                        border-radius: 12px;
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        transition: transform 0.2s;
                                    }
                                    .thumbnail-container:active .play-button {
                                        transform: scale(0.95);
                                    }
                                    .play-icon {
                                        width: 0;
                                        height: 0;
                                        border-left: 20px solid white;
                                        border-top: 12px solid transparent;
                                        border-bottom: 12px solid transparent;
                                        margin-left: 4px;
                                    }
                                </style>
                            </head>
                            <body>
                                <div class="thumbnail-container" onclick="Android.loadVideo()">
                                    <img id="thumb" class="thumbnail" alt="Trailer">
                                    <div class="play-button">
                                        <div class="play-icon"></div>
                                    </div>
                                </div>
                                <script>
                                    var img = document.getElementById('thumb');
                                    var base = 'https://img.youtube.com/vi/$trailerId/';
                                    // try the big thumbnail first, readable (cors) before plain
                                    var tries = [
                                        [base + 'maxresdefault.jpg', true],
                                        [base + 'hqdefault.jpg', true],
                                        [base + 'maxresdefault.jpg', false],
                                        [base + 'hqdefault.jpg', false]
                                    ];
                                    var idx = 0;
                                    function load() {
                                        var t = tries[idx];
                                        if (t[1]) { img.crossOrigin = 'anonymous'; }
                                        else { img.removeAttribute('crossorigin'); }
                                        img.src = t[0];
                                    }
                                    img.onerror = function () {
                                        if (idx < tries.length - 1) { idx++; load(); }
                                    };
                                    img.onload = function () {
                                        try {
                                            var w = 64;
                                            var h = Math.max(2, Math.round(w * img.naturalHeight / img.naturalWidth));
                                            var cv = document.createElement('canvas');
                                            cv.width = w; cv.height = h;
                                            var cx = cv.getContext('2d');
                                            cx.drawImage(img, 0, 0, w, h);
                                            var d = cx.getImageData(0, 0, w, h).data;
                                            function dark(r) {
                                                var s = 0;
                                                for (var i = 0; i < w; i++) {
                                                    var k = (r * w + i) * 4;
                                                    s += d[k] + d[k + 1] + d[k + 2];
                                                }
                                                return s / (w * 3) < 20;
                                            }
                                            var top = 0;
                                            while (top < h / 2 && dark(top)) top++;
                                            var bot = 0;
                                            while (bot < h / 2 && dark(h - 1 - bot)) bot++;
                                            var bar = Math.min(top, bot) / h;
                                            if (bar > 0.02) {
                                                var s = Math.min(1 / (1 - 2 * bar), 1.8);
                                                img.style.transform = 'scale(' + s.toFixed(3) + ')';
                                            }
                                        } catch (e) { /* image not readable: object-fit cover still fills the frame */ }
                                    };
                                    load();
                                </script>
                            </body>
                            </html>
                        """.trimIndent()
                        loadDataWithBaseURL("https://www.youtube-nocookie.com", placeholderHtml, "text/html", "utf-8", null)
                    }
                    bind.root.tag = "dynamic_view"
                    parent.addView(bind.root)
                }
                if (media.anime != null) {
                    val anime = media.anime
                    themePlayer?.stop()
                    themeRows.clear()
                    val themesSlot = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        tag = "dynamic_view"
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    parent.addView(themesSlot)

                    // a song link of the plain list: find its AnimeThemes video and open the video page
                    fun openThemeLink(link: String) {
                        val uri = Uri.parse(link)
                        val type = uri.getQueryParameter("type") ?: "OP"
                        val number = uri.getQueryParameter("index")?.toIntOrNull() ?: 1
                        val title = uri.getQueryParameter("title") ?: ""
                        val ctx = requireContext()
                        android.widget.Toast.makeText(
                            ctx, R.string.themes_finding, android.widget.Toast.LENGTH_SHORT
                        ).show()
                        viewLifecycleOwner.lifecycleScope.launch {
                            // 1. AnimeThemes video
                            val songs = withContext(Dispatchers.IO) {
                                runCatching { ThemeSongs.loadAnimeThemes(media) }
                                    .getOrDefault(emptyList())
                            }
                            val hit = ThemeSongs.match(songs, type, number, title)
                            if (hit != null && _binding != null) {
                                themePlayer?.stop()
                                ThemeVideoActivity.open(ctx, hit, media.mainName())
                                return@launch
                            }
                            val reason = ThemeSongs.lastError

                            // 2. the same song on YouTube, played inside the app
                            val word = if (type.equals("OP", true)) "opening" else "ending"
                            val query = "${media.mainName()} $word $title"
                            val ytId = withContext(Dispatchers.IO) {
                                runCatching { ThemeSongs.findYoutubeId(query) }.getOrNull()
                            }
                            val searchUrl = "https://www.youtube.com/results?search_query=" +
                                    java.net.URLEncoder.encode(query, "utf-8")
                            if (_binding == null) return@launch
                            if (ytId != null) {
                                themePlayer?.stop()
                                ThemeVideoActivity.openYoutube(
                                    ctx, ytId, title.ifBlank { word }, media.mainName(), searchUrl
                                )
                                return@launch
                            }

                            // 3. nothing worked: say so and ask before leaving the app
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireActivity())
                                .setTitle(R.string.themes_failed_title)
                                .setMessage(
                                    getString(
                                        R.string.themes_failed_message,
                                        reason?.let { " ($it)" } ?: ""
                                    )
                                )
                                .setPositiveButton(R.string.themes_search_youtube) { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }

                    // plain text list (from MAL / Jikan), used when AnimeThemes has nothing
                    fun showLegacySongs() {
                        val markWon = Markwon.builder(requireContext())
                            .usePlugin(SoftBreakAddsNewLinePlugin.create())
                            .usePlugin(object : AbstractMarkwonPlugin() {
                                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                                    builder.linkResolver { view, link ->
                                        if (link.startsWith("theme://")) openThemeLink(link)
                                        else LinkResolverDef().resolve(view, link)
                                    }
                                }
                            }).build()

                        fun makeLink(a: String, type: String, number: Int): String {
                            val first = a.indexOf('"').let { if (it != -1) it else return a } + 1
                            val end = a.indexOf('"', first).let { if (it != -1) it else return a }
                            val name = a.subSequence(first, end).toString()
                            val enc = { s: String -> URLEncoder.encode(s, "utf-8") }
                            val youtube =
                                "https://www.youtube.com/results?search_query=${enc(name)}"
                            val link = "theme://play?type=$type&index=$number" +
                                    "&title=${enc(name)}&yt=${enc(youtube)}"
                            return "${a.subSequence(0, first)}" +
                                    "[$name]($link)" +
                                    "${a.subSequence(end, a.length)}"
                        }

                        fun makeText(textView: TextView, arr: ArrayList<String>, type: String) {
                            var op = ""
                            arr.forEachIndexed { i, line ->
                                op += "\n"
                                val number = Regex("^#?(\\d+)").find(line.trim())
                                    ?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                                op += makeLink(line, type, number)
                            }
                            op = op.removePrefix("\n")
                            textView.setOnClickListener {
                                if (textView.maxLines == 4) {
                                    ObjectAnimator.ofInt(textView, "maxLines", 100)
                                        .setDuration(950).start()
                                } else {
                                    ObjectAnimator.ofInt(textView, "maxLines", 4)
                                        .setDuration(400).start()
                                }
                            }
                            markWon.setMarkdown(textView, op)
                        }

                        if (anime.op.isNotEmpty()) {
                            val bind = ItemTitleTextBinding.inflate(
                                LayoutInflater.from(context),
                                themesSlot,
                                false
                            )
                            bind.itemTitle.setText(R.string.opening)
                            styleSectionHeading(bind.itemTitle)
                            tightTextSection(bind.itemTitle, bind.itemText)
                            makeText(bind.itemText, anime.op, "OP")
                            bind.root.tag = "dynamic_view"
                            themesSlot.addView(bind.root)
                        }


                        if (anime.ed.isNotEmpty()) {
                            val bind = ItemTitleTextBinding.inflate(
                                LayoutInflater.from(context),
                                themesSlot,
                                false
                            )
                            bind.itemTitle.setText(R.string.ending)
                            styleSectionHeading(bind.itemTitle)
                            tightTextSection(bind.itemTitle, bind.itemText)
                            makeText(bind.itemText, anime.ed, "ED")
                            bind.root.tag = "dynamic_view"
                            themesSlot.addView(bind.root)
                        }
                    }

                    // playable list (AnimeThemes): tap a row to play / pause it right here
                    fun showPlayableThemes(songs: List<ThemeSong>) {
                        themesSlot.removeAllViews()
                        val inflater = LayoutInflater.from(context)
                        val d = resources.displayMetrics.density
                        val primary = MaterialColors.getColor(
                            themesSlot,
                            androidx.appcompat.R.attr.colorPrimary
                        )
                        val tint = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x2A))
                        val player = themePlayer ?: ThemeSongPlayer(
                            onChange = { song, state, progress ->
                                themeRows.forEach { (s, row) ->
                                    if (s == song) row.render(state, progress)
                                    else row.render(ThemeSongPlayer.State.IDLE, 0f)
                                }
                            },
                            onFail = { snackString(getString(R.string.themes_cant_play)) }
                        ).also { themePlayer = it }

                        fun addGroup(titleRes: Int, group: List<ThemeSong>) {
                            if (group.isEmpty()) return
                            val head = ItemTitleTextBinding.inflate(inflater, themesSlot, false)
                            head.itemTitle.setText(titleRes)
                            styleSectionHeading(head.itemTitle)
                            (head.itemTitle.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                                it.bottomMargin = (2 * d).toInt()
                                head.itemTitle.layoutParams = it
                            }
                            head.itemText.visibility = View.GONE
                            themesSlot.addView(head.root)

                            val list = LinearLayout(requireContext()).apply {
                                orientation = LinearLayout.VERTICAL
                            }
                            themesSlot.addView(list)

                            fun fill(all: Boolean) {
                                list.removeAllViews()
                                (if (all) group else group.take(4)).forEach { song ->
                                    val rb = ItemThemeSongBinding.inflate(inflater, list, false)
                                    rb.themeBadge.text = song.slug.ifEmpty { song.type }
                                    rb.themeBadge.backgroundTintList = tint
                                    rb.themePlay.backgroundTintList = tint
                                    rb.themeTitle.text = song.title.ifEmpty { song.slug }
                                    val meta = listOfNotNull(
                                        song.artists.joinToString(", ").takeIf { it.isNotBlank() },
                                        song.episodes?.let { getString(R.string.themes_episodes, it) }
                                    ).joinToString(" · ")
                                    rb.themeMeta.text = meta
                                    rb.themeMeta.visibility =
                                        if (meta.isBlank()) View.GONE else View.VISIBLE
                                    themeRows[song] = ThemeRowHolder(rb)
                                    rb.themePlay.setOnClickListener { player.toggle(song) }
                                    rb.root.setOnClickListener {
                                        player.stop()
                                        ThemeVideoActivity.open(
                                            requireContext(), song, media.mainName()
                                        )
                                    }
                                    list.addView(rb.root)
                                }
                            }
                            fill(false)

                            if (group.size > 4) {
                                var expanded = false
                                val more = TextView(requireContext()).apply {
                                    text = getString(R.string.themes_show_all)
                                    setTextColor(primary)
                                    textSize = 14f
                                    typeface = androidx.core.content.res.ResourcesCompat
                                        .getFont(requireContext(), R.font.poppins_bold)
                                    setPadding((32 * d).toInt(), (6 * d).toInt(), (32 * d).toInt(), (6 * d).toInt())
                                    setOnClickListener {
                                        expanded = !expanded
                                        text = getString(
                                            if (expanded) R.string.themes_show_less
                                            else R.string.themes_show_all
                                        )
                                        fill(expanded)
                                    }
                                }
                                themesSlot.addView(more)
                            }
                        }

                        addGroup(R.string.opening, songs.filter { it.isOpening })
                        addGroup(R.string.ending, songs.filter { !it.isOpening })
                    }

                    if (anime.op.isNotEmpty() || anime.ed.isNotEmpty()) showLegacySongs()
                    if (media.id != 0) {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val songs = runCatching { ThemeSongs.loadAnimeThemes(media) }
                                .getOrDefault(emptyList())
                            withContext(Dispatchers.Main) {
                                if (songs.isNotEmpty() && _binding != null) showPlayableThemes(songs)
                            }
                        }
                    }
                }

                if (media.genres.isNotEmpty()) {
                    val bind = ActivityGenreBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.root.tag = "dynamic_view"
                    val adapter = GenreAdapter(type)
                    
                    bind.mediaInfoGenresRecyclerView.adapter = adapter
                    bind.mediaInfoGenresRecyclerView.layoutManager =
                        GridLayoutManager(requireActivity(), (screenWidth / 156f).toInt())

                    genreModel.doneListener = {
                        MainScope().launch {
                            bind.mediaInfoGenresProgressBar.visibility = View.GONE
                        }
                    }
                    if (genreModel.genres != null) {
                        adapter.genres = genreModel.genres!!
                        adapter.pos = ArrayList(genreModel.genres!!.keys)
                        if (genreModel.done) genreModel.doneListener?.invoke()
                    }
                    lifecycleScope.launch(Dispatchers.IO) {
                        genreModel.loadGenres(media.genres) {
                            MainScope().launch {
                                adapter.addGenre(it)
                            }
                        }
                    }
                    parent.addView(bind.root)
                }

                if (media.tags.isNotEmpty()) {
                    val bind = ItemTitleChipgroupBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.root.tag = "dynamic_view"
                    val hideSpoilerTags = PrefManager.getVal<Boolean>(PrefName.HideSpoilerTags)
                    val hasSpoilers = media.tagsIsSpoiler.any { it }
                    var allSpoilersRevealed = false
                    val spoilerChipUpdaters = mutableListOf<(Boolean) -> Unit>()

                    bind.itemTitle.setText(R.string.tags)
                    if (hasSpoilers && hideSpoilerTags) {
                        bind.itemTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.format_spoiler_24, 0)
                        bind.itemTitle.compoundDrawablePadding = 8
                        bind.itemTitle.setSafeOnClickListener {
                            allSpoilersRevealed = !allSpoilersRevealed
                            spoilerChipUpdaters.forEach { it(allSpoilersRevealed) }
                            val msg = if (allSpoilersRevealed) R.string.show_spoilers else R.string.hide_spoilers
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }

                    for (position in media.tags.indices) {
                        val chip = ItemChipBinding.inflate(
                            LayoutInflater.from(context),
                            bind.itemChipGroup,
                            false
                        ).root
                        val isSpoiler = media.tagsIsSpoiler.getOrNull(position) == true
                        var isRevealed = !hideSpoilerTags || !isSpoiler

                        fun updateChipAppearance() {
                            if (isSpoiler && !isRevealed) {
                                chip.text = chip.context.getString(R.string.spoiler_tag_placeholder)
                                chip.setChipIconResource(R.drawable.format_spoiler_24)
                                chip.isChipIconVisible = true
                                chip.alpha = 0.75f
                            } else {
                                chip.text = media.tags[position]
                                chip.isChipIconVisible = false
                                chip.alpha = 1.0f
                            }
                        }

                        updateChipAppearance()

                        if (isSpoiler && hideSpoilerTags) {
                            spoilerChipUpdaters.add { reveal ->
                                isRevealed = reveal
                                updateChipAppearance()
                            }
                        }

                        chip.setSafeOnClickListener {
                            if (isSpoiler && !isRevealed) {
                                isRevealed = true
                                updateChipAppearance()
                            } else {
                                ContextCompat.startActivity(
                                    chip.context,
                                    Intent(chip.context, SearchActivity::class.java)
                                        .putExtra("type", type)
                                        .putExtra("sortBy", Anilist.sortBy[2])
                                        .putExtra("tag", media.tags[position].substringBefore(" :"))
                                        .putExtra("search", true)
                                        .also {
                                            if (media.isAdult) {
                                                if (!Anilist.adult) Toast.makeText(
                                                    chip.context,
                                                    currActivity()?.getString(R.string.content_18),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                it.putExtra("hentai", true)
                                            }
                                        },
                                    null
                                )
                            }
                        }
                        chip.setOnLongClickListener {
                            if (isSpoiler && isRevealed && hideSpoilerTags) {
                                isRevealed = false
                                updateChipAppearance()
                                true
                            } else {
                                copyToClipboard(media.tags[position])
                                true
                            }
                        }
                        bind.itemChipGroup.addView(chip)
                    }
                    parent.addView(bind.root)
                }

                if (!media.rankings.isNullOrEmpty()) {
                    val bind = ItemTitleChipgroupBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.root.tag = "dynamic_view"
                    bind.itemTitle.text = "Rankings"
                    media.rankings?.forEach { rank ->
                        val chip = ItemChipBinding.inflate(
                            LayoutInflater.from(context),
                            bind.itemChipGroup,
                            false
                        ).root
                        val rawContext = rank.context?.takeIf { it.isNotBlank() }
                            ?: when (rank.type) {
                                "POPULAR" -> "most popular"
                                "RATED" -> "highest rated"
                                else -> ""
                            }
                        val formattedContext = rawContext.split(" ")
                            .filter { it.isNotBlank() }
                            .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }

                        val isAllTime = rank.allTime == true || formattedContext.contains("All Time", ignoreCase = true)
                        val seasonName = rank.season?.name?.lowercase()?.replaceFirstChar(Char::titlecase)

                        val timePeriod = when {
                            isAllTime -> if (formattedContext.contains("All Time", ignoreCase = true)) "" else "All Time"
                            seasonName != null && rank.year != null -> "$seasonName ${rank.year}"
                            rank.year != null -> "${rank.year}"
                            seasonName != null -> seasonName
                            else -> ""
                        }

                        val rankingText = if (timePeriod.isNotEmpty()) {
                            "$formattedContext $timePeriod"
                        } else {
                            formattedContext
                        }

                        chip.text = "#${rank.rank} $rankingText"
                        bind.itemChipGroup.addView(chip)
                    }
                    parent.addView(bind.root)
                }

                if (!media.externalLinks.isNullOrEmpty()) {
                    val bind = ItemTitleChipgroupBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    )
                    bind.itemTitle.setText(R.string.external_links)
                    for (link in media.externalLinks!!) {
                        val url = link.url ?: continue
                        val chip = ItemChipBinding.inflate(
                            LayoutInflater.from(context),
                            bind.itemChipGroup,
                            false
                        ).root
                        chip.text = link.site
                        chip.setSafeOnClickListener {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        chip.setOnLongClickListener { copyToClipboard(url); true }
                        bind.itemChipGroup.addView(chip)
                    }
                    bind.root.tag = "dynamic_view"
                    parent.addView(bind.root)
                }

                if ((!media.relations.isNullOrEmpty() || media.sequel != null || media.prequel != null)) {
                    if (media.sequel != null || media.prequel != null) {
                        ItemQuelsBinding.inflate(
                            LayoutInflater.from(context),
                            parent,
                            false
                        ).apply {

                            if (media.sequel != null) {
                                mediaInfoSequel.visibility = View.VISIBLE
                                mediaInfoSequelImage.loadImage(
                                    media.sequel!!.banner ?: media.sequel!!.cover
                                )
                                mediaInfoSequel.setSafeOnClickListener {
                                    ContextCompat.startActivity(
                                        requireContext(),
                                        Intent(
                                            requireContext(),
                                            MediaDetailsActivity::class.java
                                        ).putExtra(
                                            "media",
                                            media.sequel as Serializable
                                        ), null
                                    )
                                }
                            }
                            if (media.prequel != null) {
                                mediaInfoPrequel.visibility = View.VISIBLE
                                mediaInfoPrequelImage.loadImage(
                                    media.prequel!!.banner ?: media.prequel!!.cover
                                )
                                mediaInfoPrequel.setSafeOnClickListener {
                                    ContextCompat.startActivity(
                                        requireContext(),
                                        Intent(
                                            requireContext(),
                                            MediaDetailsActivity::class.java
                                        ).putExtra(
                                            "media",
                                            media.prequel as Serializable
                                        ), null
                                    )
                                }
                            }
                            root.tag = "dynamic_view"
                            parent.addView(root)
                        }
                    }
                }

                val isAnimeMedia = media.anime != null
                ItemQuelsBinding.inflate(
                    LayoutInflater.from(context),
                    parent,
                    false
                ).apply {
                    if (isAnimeMedia) {
                        val watchOrderImage = media.banner ?: media.cover
                        val newsImage = media.relations?.firstOrNull { it.cover != null && it.cover != media.cover }?.cover
                            ?: media.characters?.firstOrNull()?.image
                            ?: media.relations?.firstOrNull { it.banner != null && it.banner != media.banner }?.banner
                            ?: media.cover?.takeIf { it != watchOrderImage }
                            ?: media.banner
                            ?: media.cover

                        mediaInfoPrequel.visibility = View.VISIBLE
                        mediaInfoPrequelTitle.text = getString(R.string.watch_order)
                        mediaInfoPrequelImage.loadImage(watchOrderImage)
                        mediaInfoPrequel.setSafeOnClickListener {
                            if (!isAdded || context == null) return@setSafeOnClickListener
                            if (childFragmentManager.findFragmentByTag("watch_order") == null) {
                                MediaContentBottomSheet.newWatchOrderInstance(media)
                                    .show(childFragmentManager, "watch_order")
                            }
                        }

                        mediaInfoSequel.visibility = View.VISIBLE
                        mediaInfoSequelTitle.text = getString(R.string.news)
                        mediaInfoSequelImage.loadImage(newsImage)
                        mediaInfoSequel.setSafeOnClickListener {
                            if (!isAdded || context == null) return@setSafeOnClickListener
                            if (childFragmentManager.findFragmentByTag("news") == null) {
                                MediaContentBottomSheet.newNewsInstance(media)
                                    .show(childFragmentManager, "news")
                            }
                        }
                    } else {
                        val newsImage = media.banner ?: media.cover
                        mediaInfoPrequel.visibility = View.VISIBLE
                        mediaInfoPrequelTitle.text = getString(R.string.news)
                        mediaInfoPrequelImage.loadImage(newsImage)
                        mediaInfoPrequel.setSafeOnClickListener {
                            if (!isAdded || context == null) return@setSafeOnClickListener
                            if (childFragmentManager.findFragmentByTag("news") == null) {
                                MediaContentBottomSheet.newNewsInstance(media)
                                    .show(childFragmentManager, "news")
                            }
                        }
                    }

                    root.tag = "dynamic_view"
                    parent.addView(root)
                }

                if (!media.review.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        val adapter = GroupieAdapter()
                        media.review!!.forEach { adapter.add(ReviewAdapter(it)) }
                        itemTitle.setText(R.string.reviews)
                        itemRecycler.adapter = adapter
                        itemRecycler.layoutManager = LinearLayoutManager(requireContext())
                        itemMore.visibility = View.VISIBLE
                        itemMore.setSafeOnClickListener {
                            startActivity(
                                Intent(requireContext(), ReviewActivity::class.java)
                                    .putExtra("mediaId", media.id)
                            )
                        }
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }

                if (!media.relations.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        itemRecycler.adapter =
                            MediaAdaptor(0, media.relations!!, requireActivity())
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
                if (!media.characters.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        itemTitle.setText(R.string.characters)
                        itemRecycler.adapter =
                            CharacterAdapter(media.characters!!)
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
                if (!media.staff.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        itemTitle.setText(R.string.staff)
                        itemRecycler.adapter =
                            AuthorAdapter(media.staff!!)
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }

                if (!media.recommendations.isNullOrEmpty()) {
                    ItemTitleRecyclerBinding.inflate(
                        LayoutInflater.from(context),
                        parent,
                        false
                    ).apply {
                        itemTitle.setText(R.string.recommended)
                        itemRecycler.adapter =
                            MediaAdaptor(0, media.recommendations!!, requireActivity())
                        itemRecycler.layoutManager = LinearLayoutManager(
                            requireContext(),
                            LinearLayoutManager.HORIZONTAL,
                            false
                        )
                        root.tag = "dynamic_view"
                        parent.addView(root)
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val cornerTop = ObjectAnimator.ofFloat(binding.root, "radius", 0f, 32f).setDuration(200)
            val cornerNotTop =
                ObjectAnimator.ofFloat(binding.root, "radius", 32f, 0f).setDuration(200)
            var cornered = true
            cornerTop.start()
            binding.mediaInfoScroll.setOnScrollChangeListener { v, _, _, _, _ ->
                if (!v.canScrollVertically(-1)) {
                    if (!cornered) {
                        cornered = true
                        cornerTop.start()
                    }
                } else {
                    if (cornered) {
                        cornered = false
                        cornerNotTop.start()
                    }
                }
            }
        }

        super.onViewCreated(view, null)
    }

    override fun onResume() {
        binding.mediaInfoProgressBar.isGone = loaded
        super.onResume()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}

/** UI state of one playable song row. */
private class ThemeRowHolder(private val b: ItemThemeSongBinding) {
    private var showingPause = false

    fun render(state: ThemeSongPlayer.State, progress: Float) {
        val p = b.themeProgress
        when (state) {
            ThemeSongPlayer.State.IDLE -> {
                p.visibility = View.GONE
                setIcon(false)
            }

            ThemeSongPlayer.State.LOADING -> {
                setIndeterminate(true)
                p.visibility = View.VISIBLE
                setIcon(false)
            }

            ThemeSongPlayer.State.PLAYING, ThemeSongPlayer.State.PAUSED -> {
                setIndeterminate(false)
                p.setProgressCompat((progress * 1000).toInt(), false)
                p.visibility = View.VISIBLE
                setIcon(state == ThemeSongPlayer.State.PLAYING)
            }
        }
    }

    private fun setIndeterminate(on: Boolean) {
        val p = b.themeProgress
        if (p.isIndeterminate == on) return
        p.visibility = View.INVISIBLE
        p.isIndeterminate = on
        p.visibility = View.VISIBLE
    }

    private fun setIcon(pause: Boolean) {
        if (pause == showingPause) return
        showingPause = pause
        val icon = b.themePlay
        icon.animate().cancel()
        icon.animate().scaleX(0.6f).scaleY(0.6f).setDuration(80).withEndAction {
            icon.setImageResource(if (pause) R.drawable.ic_theme_pause else R.drawable.ic_theme_play)
            icon.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(2f)).setDuration(180).start()
        }.start()
    }
}
